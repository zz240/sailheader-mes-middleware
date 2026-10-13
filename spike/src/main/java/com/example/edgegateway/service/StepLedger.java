package com.example.edgegateway.service;

import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.BatchStamp;
import com.example.edgegateway.model.Job;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息步 / 运行**簿记**（#145）：这一步跑了多久、失败了几次、该记哪一行。
 *
 * <p>它是 {@link MessageHistoryService} 上面一层**薄编排**——只把 exchange 上的现场（计时起点、
 * 失败计数、批次戳、上下文）翻成落库调用参数，落库本身与幂等口径仍归 {@link MessageHistoryService}。
 * 装配与片段两侧都只写 {@code ledger.xxx(...)}，不再各自算这些账。</p>
 *
 * <p>三条口径在这里定，改之前先读：</p>
 * <ol>
 *   <li><b>耗时量的是动作本身</b>（#134）：起点打在动作执行之前，重投成功的那一步取**本次**尝试的
 *       起点（所以退避等待不算进去）；没测就记 {@code null}，不编成 {@code 0}。</li>
 *   <li><b>尝试次数按步骤归零</b>（#109）：不读 Camel 的 {@code CamelRedeliveryCounter}（Camel 4 把它
 *       写在 message header 上，每次重投会被 remove/decrement，读它恒为 {@code null + 1}）。</li>
 *   <li><b>失败步也带批次戳与耗时</b>（#134 / #139）：只记成功那条路的话，「哪几条是同一批」在失败
 *       那一格上就断了。</li>
 * </ol>
 */
@Component
class StepLedger {

    private final MessageHistoryService messageHistory;
    private final JobRunGuard runGuard;

    StepLedger(MessageHistoryService messageHistory, JobRunGuard runGuard) {
        this.messageHistory = messageHistory;
        this.runGuard = runGuard;
    }

    // ---- 计时（#134）----

    /** 下一次重投开始前的计时起点：起点打在退避之后，退避等待因此不算动作耗时。 */
    void restartStepTiming(Exchange exchange) {
        exchange.setProperty(RouteKeys.STEP_STARTED_AT, System.currentTimeMillis());
    }

    /**
     * 标记「这一步的动作从这里开始」：起点打在动作执行之前，落库时与之相减。
     *
     * <p>同时清除上一步已结算的耗时值——该属性挂在 exchange 上，不清除会传递到下一步。</p>
     */
    void beginStep(Exchange exchange) {
        exchange.removeProperty(RouteKeys.STEP_DURATION_MS);
        exchange.setProperty(RouteKeys.STEP_STARTED_AT, System.currentTimeMillis());
    }

    /** 结算本步动作耗时：把「起点到现在」落成显式值、清掉起点；没有起点（未计时）时什么都不做。 */
    void settleStepDuration(Exchange exchange) {
        Long started = exchange.getProperty(RouteKeys.STEP_STARTED_AT, Long.class);
        if (started == null) {
            return;
        }
        exchange.setProperty(RouteKeys.STEP_DURATION_MS, System.currentTimeMillis() - started);
        exchange.removeProperty(RouteKeys.STEP_STARTED_AT);
    }

    /**
     * 本步动作耗时，落库时调。
     *
     * <p>**开着的起点优先**：重投过又成功的那一步，显式值是上一次失败尝试的时长，而起点是本次成功尝试
     * 的（{@link #restartStepTiming}），要的是后者。起点不在（已结算 / 压根没计时）才退回显式值；
     * 两个都没有时给 {@code null}——「没测」不编成 {@code 0}。</p>
     */
    Long stepDuration(Exchange exchange) {
        Long started = exchange.getProperty(RouteKeys.STEP_STARTED_AT, Long.class);
        if (started != null) {
            return System.currentTimeMillis() - started;
        }
        return exchange.getProperty(RouteKeys.STEP_DURATION_MS, Long.class);
    }

    // ---- 尝试次数（#109）----

    /**
     * 一次投递失败（含最后一次）：计数 + **结算本次尝试的动作耗时**（#134）。
     *
     * <p>结算放在异常回调里而不是落库那一刻，是因为落库发生在重试耗尽之后、退避等待之后——
     * 在那里现算的话，一个「连接超时 30 秒、退避 1 秒、重试 2 次」的失败步会写成一个更大的数，
     * 而那正是 ADR-0016 决策 7 拒绝的**步间隔**口径。取最后一次尝试，退避不进去。</p>
     */
    void noteAttemptFailure(Exchange exchange) {
        countAttempt(exchange);
        settleStepDuration(exchange);
    }

    /**
     * 记一次失败尝试：异常回调在每次投递失败时都会走一次，末次（重试耗尽）也在其中。
     * 计数按**步骤**归零——同一条消息里前面步骤的重投次数不能算到当前步骤头上
     * （入口链 {@code handled=true} 失败后链还会往下走，后一个步骤成功时极易把前一步骤的计数算到自己头上）。
     */
    private void countAttempt(Exchange exchange) {
        Integer current = actionIndex(exchange);
        int occurred = failedAttempts(exchange, current);
        exchange.setProperty(RouteKeys.FAILED_ATTEMPTS_STEP, current);
        exchange.setProperty(RouteKeys.FAILED_ATTEMPTS, occurred + 1);
    }

    /** 这个步骤（动作序号）已失败几次；计数属于别的步骤（或还没计过）时为 0。 */
    private int failedAttempts(Exchange exchange, Integer step) {
        Integer counted = exchange.getProperty(RouteKeys.FAILED_ATTEMPTS_STEP, Integer.class);
        if (step == null || !step.equals(counted)) {
            return 0;
        }
        Integer occurred = exchange.getProperty(RouteKeys.FAILED_ATTEMPTS, Integer.class);
        return occurred == null ? 0 : occurred;
    }

    /**
     * 失败步的**总尝试次数**（#109）= 该步失败次数（这个步骤每次投递都失败，最后一次也进了异常回调）。
     */
    int attemptsOnFailure(Exchange exchange) {
        int failed = failedAttempts(exchange, actionIndex(exchange));
        return Math.max(failed, 1);
    }

    /** 成功步的总尝试次数（#109）= 该步之前失败几次 + 这次成功（成功不经过异常回调）。 */
    int attemptsOnSuccess(Exchange exchange) {
        return failedAttempts(exchange, actionIndex(exchange)) + 1;
    }

    /** 当前这一步是哪个动作序号（{@code edge.actionIndex}）。 */
    Integer actionIndex(Exchange exchange) {
        return exchange.getProperty(MessageHistoryService.ACTION_INDEX, Integer.class);
    }

    // ---- 运行 / 消息的开始与结束 ----

    /**
     * 开一次运行：建运行行、把运行标识挂上 exchange（后续所有消息步都挂它）。
     *
     * <p>顺手把运行标识回填给 {@link JobRunGuard}（#146）——「跳过」那条日志要写出当前在跑的是哪一次
     * 运行。回填只走 {@code tryAcquire} 拿到占用凭据的那条路（{@link JobRunGuard#bindRun} 自己判空），
     * 断点续跑那种没走互斥的运行不受影响。</p>
     */
    void beginRun(Exchange ex, Job job) {
        Long runId = messageHistory.startRun(job.getId(), ex.getMessage().getBody()).getId();
        ex.setProperty(MessageHistoryService.RUN_ID, runId);
        JobRunGuard.bindRun(runGuard, job.getId(), runId);    }

    /**
     * 创建一条消息：消息标识、业务键、父消息标识、批内序号都归
     * {@link ElementMessageFactory} 管（#139）。
     *
     * <p>读库这条路上父消息与批内序号都是空的（一行一条消息、读取批没有「第几条」的语义）。</p>
     *
     * <p>消息体的取用规则（业务键配置 + 继承来的业务键）见 {@link ElementMessageFactory.ElementSpec#read}。</p>
     */
    void beginMessage(Exchange ex, String businessKeyConfig) {
        ElementMessageFactory.InitializedMessage message = ElementMessageFactory.of(
                ex.getMessage().getBody(),
                ElementMessageFactory.ElementSpec.read(
                        businessKeyConfig, ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class)));
        ex.setProperty(MessageHistoryService.MESSAGE_ID, message.messageId());
        ex.setProperty(MessageHistoryService.BUSINESS_KEY, message.businessKey());
        ex.setProperty(MessageHistoryService.STEP_INPUT, message.body());
    }

    /**
     * 这一行的**批次戳**（#139）：批号取自 exchange 上当前那个（随消息在链上**继承到下一个基数变化
     * 动作为止**），批内序号与父消息标识由基数变化动作用自己写进 exchange（读库那条路两个都空）。
     *
     * <p>读库那档「每读一批一个新号」落在 {@link ReadWriteFragments#readBatch} 上：一批里的每条源行
     * 共用同一个号，于是「哪几条是同一批」在库里答得出来。</p>
     */
    BatchStamp batchStamp(Exchange ex) {
        return new BatchStamp(
                ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class),
                ex.getProperty(MessageHistoryService.BATCH_SEQ, Integer.class),
                ex.getProperty(MessageHistoryService.PARENT_MESSAGE_ID, String.class));
    }

    /** 记一条成功消息步，输出取当前 body。 */
    void recordSuccessStep(Exchange ex, Action action, Object input) {
        recordSuccessStep(ex, action, input, ex.getMessage().getBody());
    }

    /**
     * 记一条成功消息步。{@code data} 单独传（而不是一律取当前 body）：HTTP 步收尾时 body 已被还原成请求体，
     * 而 {@code data_snapshot} 要记的是**响应**（见 {@link ReadWriteFragments#captureResponse}）。
     *
     * <p>耗时从 {@link #stepDuration} 取——起点是本步动作执行之前打的那一个，重投过的步取的是**这次成功
     * 那次尝试**（见该方法里那条优先级说明）。记完就把起点收掉，理由见方法体里那段。</p>
     */
    void recordSuccessStep(Exchange ex, Action action, Object input, Object data) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        String messageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
        if (runId == null || messageId == null) {
            return; // 未创建消息（如 MQ_CONSUME 入口反序列化失败）时不记消息步
        }
        Long durationMs = stepDuration(ex);
        // 记完这一步就把**计时起点**收掉（#134）：终点之后链上再出错（落运行、推水位那几处都不属于
        // 任何一步），onException 会拿最后一步的序号记一条 FAILED——那时不该把「上一步开始到现在的
        // 时长」算到它头上（那是个看着像模像样的错数），该给 `—`。
        // 只收起点的、**不动**已经结算好的那个显式值：READ_SQL 同一批的后续源行还要用它。
        ex.removeProperty(RouteKeys.STEP_STARTED_AT);
        messageHistory.recordSuccess(runId, messageId, action.getActionIndex(), action.getName(),
                input, data,
                ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class), attemptsOnSuccess(ex),
                durationMs, batchStamp(ex));
    }

    /**
     * 失败那一刻的收尾：先把运行标成 FAILED，再记失败的那一步（以及合并那一批的每条成员各一行）。
     *
     * <p>三处口径：</p>
     * <ol>
     *   <li>没产生任何源行时（READ_SQL 在 split 前失败）用**批量占位消息标识**记一条 FAILED 步；</li>
     *   <li>{@code edge.batchNoSummary} 置上时**只逐条记**（还原失败那一档，摘要行归出站）；</li>
     *   <li>合并那一批（#142）：请求失败时这一批**每条消息各记一行** FAILED（同因，各自带自己那一片），
     *       摘要行由上一条路记（那时 MESSAGE_ID 已被置成 {@code batch:<批号>}）。两条路写同一行时是
     *       upsert，不会重。</li>
     * </ol>
     */
    void recordFailure(Exchange ex) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        if (runId != null) {
            messageHistory.finishRun(runId, "FAILED", rootMessage(ex));
        }
        String messageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
        if (messageId == null) {
            messageId = ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class);
        }
        Integer index = ex.getProperty(MessageHistoryService.ACTION_INDEX, Integer.class);
        boolean onlyMembers = Boolean.TRUE.equals(ex.getProperty(RouteKeys.BATCH_NO_SUMMARY, Boolean.class));
        if (messageId != null && index != null && !onlyMembers) {
            messageHistory.recordFailure(runId, messageId, index,
                    ex.getProperty(MessageHistoryService.ACTION_NAME, String.class),
                    rootMessage(ex),
                    ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class),
                    ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class),
                    attemptsOnFailure(ex),
                    stepDuration(ex),
                    batchStamp(ex));
        }
        List<AggregateBatch.Carrier> carriers = ex.getProperty(RouteKeys.BATCH_MANIFEST, List.class);
        if (carriers != null && !carriers.isEmpty()) {
            String error = rootMessage(ex);
            int seq = 0;
            for (AggregateBatch.Carrier carrier : carriers) {
                seq++;
                messageHistory.recordFailure(runId, carrier.messageId(), index,
                        ex.getProperty(MessageHistoryService.ACTION_NAME, String.class), error,
                        carrier.body(), carrier.businessKey(), attemptsOnFailure(ex),
                        stepDuration(ex),
                        new BatchStamp(ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class),
                                seq, carrier.parentMessageId()));
            }
        }
    }

    /**
     * 收尾一次运行：落运行状态（成功或失败）。
     *
     * <p>这里**不清攒批表**——攒批表以 {@link AggregateFragment} 为唯一写入方（#145）。清理由装配处在
     * 运行收尾时调用 {@link AggregateFragment#clearPendingBatches}，理由见那个方法的说明。</p>
     */
    void finishRun(Exchange ex, String status, String error) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        if (runId != null) {
            messageHistory.finishRun(runId, status, error);
        }
    }

    // ---- 链上通用的取用 ----

    /**
     * 把这一步的输出写回上下文（{@code context[index] = value}）——链上那几处"记一步"共用这一份。
     *
     * <p>写在 {@link #context} 旁边：它只管读写那一个属性，四个片段类与装配处不再各抄一遍。</p>
     */
    static void putContext(Exchange ex, int index, Object value) {
        Map<Integer, Object> context = context(ex);
        context.put(index, value);
        ex.setProperty(RouteKeys.CONTEXT, context);
    }

    /** 这一步是否落在断点续投的**起点之前**（早于 {@code edge.resumeFromStep} 的步骤整段跳过）。 */
    boolean isResumeSkipped(Exchange ex, int index) {
        Integer resumeFrom = ex.getProperty(MessageHistoryService.RESUME_FROM, Integer.class);
        return resumeFrom != null && index < resumeFrom;
    }

    /** 最内层那句异常的文本（顺着 cause 链走到根），落库当错误原因用。 */
    static String rootMessage(Exchange exchange) {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        if (cause == null) {
            return null;
        }
        Throwable t = cause;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? cause.getMessage() : message;
    }

    /**
     * 任务上下文：{@code context[动作序号] = 该步输出}，链上"当前数据"是 {@code context[0]}。
     *
     * <p>懒建：exchange 上还没有时，拿**当时的 body** 当 {@code context[0]} 建一份。所以建它的时机有意义
     * （出站前要先建，不然出站后再建就把响应当成任务输入了，#123）。</p>
     */
    @SuppressWarnings("unchecked")
    static Map<Integer, Object> context(Exchange exchange) {
        Object raw = exchange.getProperty(RouteKeys.CONTEXT, Object.class);
        if (raw instanceof Map<?, ?> map) {
            return (Map<Integer, Object>) map;
        }
        Map<Integer, Object> context = new LinkedHashMap<>();
        context.put(0, exchange.getMessage().getBody());
        exchange.setProperty(RouteKeys.CONTEXT, context);
        return context;
    }
}
