package com.example.edgegateway.service;

import com.example.edgegateway.config.action.AggregateConfig;
import com.example.edgegateway.model.Action;
import org.apache.camel.Exchange;
import org.apache.camel.model.ProcessorDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合并动作的片段（#145 从 {@code RouteGenerator} 搬出；语义见 #142 / ADR-0017 决策 6 的修订版）：
 * 前半段**记承运单**、后半段**打成一条报文**，以及那份「正在攒的批次」的全部归属。
 *
 * <p>位置由「攒齐的边界 = 上游读取批读完那一刻」定：前半段留在**当前这一层**（读库/拆分那几层扇出
 * 之内），每条消息各来一次；然后把扇出那几层收回来，在**批 exchange** 上打包。打完**不开新扇出**：
 * 链上从这里起只有一条消息，后面的出站动作天然只发一次。</p>
 *
 * <p><b>{@link #pendingBatches} 以本类为唯一写入方</b>（#145）：它的增加、删除与按运行前缀的兜底清理
 * 都只在本类内进行。运行收尾那一处由装配处调用（见 {@code RouteGenerator.finishRun}），
 * {@link StepLedger} 不参与清理。若允许两处清理，就会出现「先清理者清掉了后清理者正要用的数据」这类
 * 时序依赖缺陷，而这种缺陷只在批次未完成即失败的运行上才会暴露。</p>
 */
@Component
class AggregateFragment {

    private final StepLedger ledger;
    private final MessageHistoryService messageHistory;

    /**
     * 正在攒的批次（#142）：键 = {@code 运行 + 合并动作序号}。
     *
     * <p>为什么按「运行 + 动作」而不是按批号：到齐判定发生在**批 exchange** 上（读库那批的 split
     * 收尾之后），而批 exchange 上的批号与**消息身上**的批号可能不是同一个（中间隔着一个拆分动作时，
     * 消息带的是拆分换的那个号）。同一个动作在一次运行里同时只攒一批（读库循环是一批一批走的），
     * 所以这个键就是「当前这一批」。攒完即删；运行结束（成功或失败）再按运行前缀扫一次兜底。</p>
     */
    private final Map<String, AggregateBatch> pendingBatches = new ConcurrentHashMap<>();

    AggregateFragment(StepLedger ledger, MessageHistoryService messageHistory) {
        this.ledger = ledger;
        this.messageHistory = messageHistory;
    }

    /**
     * 合并动作的**前半段**：把这条消息记成一张承运单，交进「这一批」的护栏。
     *
     * <p>它必须落在**当前这一层**（读库/拆分那几层扇出之内）：每条消息各来一次，攒齐才发。断点续投
     * 跳过这一步时照样记单（标 {@code skipped}）——那一条不参与发送，但现场要还回去，它之后的步骤照跑
     * （下游引用合并那一步的输出从 {@code edge.resumeContext} 拿）。</p>
     */
    ProcessorDefinition<?> appendAggregateCollect(ProcessorDefinition<?> current, Action action) {
        int index = action.getActionIndex();
        return current.process(ex -> {
            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
            ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
            collectForAggregate(ex, index);
        });
    }

    /**
     * 合并动作的**后半段**：在批 exchange 上把这一批**打成一条报文**。
     *
     * <p>它**不认识出口**：打包完链上只有一条消息，后面的出站动作（{@code HTTP_CALL} / {@code MQ_PUBLISH}）
     * 因此天然只发一次；出口的种类与数量由链决定，换出口不用动合并配置。响应由还原（{@code RESTORE}）
     * 那一档摊回原来那 N 条。</p>
     *
     * <p>空批（读库读到 0 行 / 整批被续投跳过）标 {@link RouteKeys#BATCH_EMPTY}：调用方用一层 filter
     * 兜住，后续动作（含出站）一律不跑——不然**每个「没有新数据」的运行**都会朝目标端发一份空报文。</p>
     */
    ProcessorDefinition<?> appendAggregatePack(ProcessorDefinition<?> current, Action action,
                                               AggregateConfig aggregate) {
        int index = action.getActionIndex();
        return current.process(ex -> {
            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
            ledger.beginStep(ex); // #134：计时起点打在打包之前
            List<AggregateBatch.Carrier> carriers = takeBatch(ex, index);
            if (carriers.isEmpty() || carriers.stream().allMatch(AggregateBatch.Carrier::skipped)) {
                // 空批 / 整批被断点续投跳过：这一趟没有要发的东西，后面那层 filter 会兜住
                ex.setProperty(RouteKeys.BATCH_EMPTY, true);
                return;
            }
            Object wholeBatch = carriers.stream().map(AggregateBatch.Carrier::body).toList();
            Map<String, Object> packed = new LinkedHashMap<>();
            packed.put(aggregate.bodyField(), wholeBatch);
            // 容量闸（#123 口径 4 / #141 的列容量）：整批报文装不下就当场失败、绝不写半截
            messageHistory.requireCaptureFitsSnapshot(index, packed);
            ex.setProperty(MessageHistoryService.STEP_INPUT, wholeBatch);
            ex.getMessage().setBody(packed);
            StepLedger.putContext(ex, index, packed);
            ex.setProperty(RouteKeys.BATCH_MANIFEST, carriers);
            // 这一行是「整批 → 打包报文」：输入是整批（每条各自那一片），输出是打包出来的那一条报文。
            // **整份请求 / 整份响应**那一行（摘要行）归**出站**动作记（决策 6 的修订）
            ledger.recordSuccessStep(ex, action, wholeBatch, packed);
        });
    }

    /** 攒批键：{@code 运行 + 合并动作序号}（同一次运行里，一个合并动作同时只攒一批，见那个字段的说明）。 */
    private static String batchKey(Long runId, int index) {
        return runId + ":" + index;
    }

    /**
     * 把这条消息记成一张承运单。没有运行标识（还没 beginRun）时什么都不做——那种 exchange
     * 也不会走到出站那一步。
     */
    private void collectForAggregate(Exchange ex, int index) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        if (runId == null) {
            return;
        }
        String messageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
        String parentMessageId = ex.getProperty(MessageHistoryService.PARENT_MESSAGE_ID, String.class);
        Integer shares = ex.getProperty(RouteKeys.BATCH_EXPECTED, Integer.class);
        pendingBatches.computeIfAbsent(batchKey(runId, index), key -> new AggregateBatch())
                .add(new AggregateBatch.Carrier(
                        messageId,
                        ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class),
                        ex.getProperty(MessageHistoryService.BATCH_SEQ, Integer.class),
                        parentMessageId,
                        ex.getMessage().getBody(),
                        // 上下文**拷一份**：split 出来的兄弟 exchange 可能共用同一个 Map，现场不能被别人改掉
                        new LinkedHashMap<>(StepLedger.context(ex)),
                        parentMessageId != null ? parentMessageId : messageId,
                        shares == null ? 1 : shares,
                        ledger.isResumeSkipped(ex, index)));
    }

    /**
     * 判齐 + 转批号 + 把这一批的成员清单交给打包那一步。
     *
     * <p>三种情形：**这一批没产出消息**（读库读到空批）→ 返回空的清单；**整批被断点续投跳过** →
     * 照样返回（调用方按 {@code skipped} 判断，不发也不记）；**齐了** → 现生成新批号（合并是基数变化动作，
     * ADR-0017 决策 2/3 的转手）、给打包出来那一条消息一个**自己的消息标识**，返回成员清单。</p>
     *
     * <p>凑不满（上游有消息没走到这一步）立即失败：**不透传不完整的批次**（决策 8）。到这一步说明整次运行
     * 本来就是失败的，这里再把它说清楚一次。</p>
     */
    private List<AggregateBatch.Carrier> takeBatch(Exchange ex, int index) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        AggregateBatch batch = runId == null ? null : pendingBatches.remove(batchKey(runId, index));
        List<AggregateBatch.Carrier> carriers = batch == null ? List.of() : batch.carriers();
        if (batch != null && batch.arrived() > 0 && !batch.complete()) {
            throw new IllegalStateException("合并这一步没凑齐一批：应当 " + batch.expected() + " 条，只到了 "
                    + batch.arrived() + " 条——按失败处理，不发半批（#142）");
        }
        if (!carriers.isEmpty()) {
            // 批号在这一步**转手**；打包出来那一条消息带新号，它自己的消息标识现生成——
            // 摘要行的 `batch:<批号>` 留给**出站**那一步（ADR-0017 决策 6 的修订）
            ex.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
            ex.setProperty(MessageHistoryService.MESSAGE_ID, UUID.randomUUID().toString());
            ex.setProperty(MessageHistoryService.BATCH_SEQ, null);
            ex.setProperty(MessageHistoryService.PARENT_MESSAGE_ID, null);
        }
        return carriers;
    }

    /**
     * 认出「这一条是合并打出来的整批载体」：出站动作（{@code HTTP_CALL} / {@code MQ_PUBLISH}）
     * 执行前调它，把这一行的消息标识换成**摘要行**的 {@code batch:<批号>}——整份请求与整份响应因此
     * 只落在这一行上（ADR-0017 决策 6 的修订：摘要行归**出站**那一步，不再归合并）。
     *
     * <p>没有成员清单（链上没合并动作）时什么都不做：普通出站动作照旧记自己那条消息的行。</p>
     */
    void markBatchCarrier(Exchange ex) {
        if (ex.getProperty(RouteKeys.BATCH_MANIFEST) == null) {
            return;
        }
        String batchId = ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class);
        if (batchId != null) {
            ex.setProperty(MessageHistoryService.MESSAGE_ID,
                    MessageHistoryService.SUMMARY_MESSAGE_PREFIX + batchId);
        }
    }

    /** 运行结束（成功或失败）时清理该运行仍挂着的攒批：未完成批次的数据不应残留在实例上。 */
    void clearPendingBatches(Long runId) {
        if (runId != null) {
            pendingBatches.keySet().removeIf(key -> key.startsWith(runId + ":"));
        }
    }
}
