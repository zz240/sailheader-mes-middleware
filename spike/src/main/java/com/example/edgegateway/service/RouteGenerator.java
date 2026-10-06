package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.config.ActionConfig;
import com.example.edgegateway.config.AggregateConfig;
import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.config.FanoutConfig;
import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.HttpCallConfig;
import com.example.edgegateway.config.HttpDataSourceConfig;
import com.example.edgegateway.config.MqConsumeConfig;
import com.example.edgegateway.config.MqPublishConfig;
import com.example.edgegateway.config.ReadSqlConfig;
import com.example.edgegateway.config.SplitConfig;
import com.example.edgegateway.config.WriteUpsertConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.BatchStamp;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleConfig;
import com.example.edgegateway.model.WatermarkType;
import com.example.edgegateway.service.sql.SqlDialect;
import com.example.edgegateway.service.sql.SqlDialects;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.ExpressionBuilder;
import org.apache.camel.model.LoopDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.SplitDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 路由生成器（DS-8 片段组合）：按动作种类逐段追加 Camel 片段。
 *
 * <p>入口：链首为 MQ_CONSUME → 消费入口（MQ 消费主路由 + direct 重放路由）；否则
 * 直连入口（direct；自动触发由 JobScheduler 依调度配置投递）。READ_SQL 会 split(body())，其后的片段在行内执行。
 * 错误处理统一：onException（重试/退避）+ 消息步 FAILED 记录 + 运行 FAILED 标记。
 * 每个任务额外生成"断点续跑路由"（direct:job-&lt;id&gt;-resume），供失败消息步精确重投。</p>
 */
@Component
public class RouteGenerator {

    private static final String CONTEXT = "edge.context";
    private static final String SKIP_FLAG = "edge.skipFragment";
    /** 某个步骤已失败了几次（#109）：onExceptionOccurred 自计数，写 {@code message_step.attempt} 时读它。 */
    private static final String FAILED_ATTEMPTS = "edge.failedAttempts";
    /** {@link #FAILED_ATTEMPTS} 是哪个步骤的失败次数——读的时候要对上号，否则会串到别的步骤头上。 */
    private static final String FAILED_ATTEMPTS_STEP = "edge.failedAttemptsStep";
    /** 当前批次下界（epoch 毫秒 / 自增值），每批处理后更新；持久化用 {@link WatermarkStore}。 */
    private static final String WATERMARK = "edge.readWatermark";
    /** 是否还有下一批（keyset 分页未扫完）。 */
    private static final String HAS_MORE = "edge.hasMoreBatches";
    /**
     * 本步动作**开始执行**的时刻（epoch 毫秒，#134）。落库那一刻与它相减，得到这一步的
     * {@code duration_ms}——量的是动作本身，不是相邻消息步的间隔（含排队与开销，另一个量）。
     */
    private static final String STEP_STARTED_AT = "edge.stepStartedAt";
    /**
     * 已经**结算好**的本步动作耗时（#134）。READ_SQL 的执行在 split 之前就结束了、落库却发生在每条源行上，
     * 所以在 split 之前结算一次，各源行共用这一个数（split 会把 exchange 属性一并拷给子 exchange）——
     * 不这么做，第二条源行量到的就是「读库 + 前一条消息跑完」的时长。
     */
    private static final String STEP_DURATION_MS = "edge.stepDurationMs";
    /**
     * 拆分这一批的**来源对象**（#140）：{@code keepParent} 要摊平的是**父消息**的外层字段，
     * 而父消息是按配置（sourceStep + sourcePath）取到的那个对象——不是数组里的某个元素。
     */
    private static final String SPLIT_SOURCE = "edge.splitSource";
    /**
     * 这一行**本该**摊出几条消息（#142）：读库那批一行一条（没人写它，缺省 1），拆分动作在 split
     * 之前改写成摊出来的条数。合并动作靠它判「这一批齐了没有」——按行去重后合计，所以同一行拆出来的
     * k 条带的是同一个数（去重靠父消息标识）。
     */
    private static final String BATCH_EXPECTED = "edge.batchExpected";
    /**
     * 合并动作攒到的那批**成员清单**（#142）：每条只有身份与它自己那一片。
     * 名字的唯一定义在 {@link MessageHistoryService#BATCH_MANIFEST}——扇回、出站失败时的补记、
     * 以及打包重发那条路由都要用它。**不进报文、不进快照**（绝不能污染发给终端的 JSON）。
     */
    private static final String BATCH_MANIFEST = MessageHistoryService.BATCH_MANIFEST;
    /**
     * 这一批是**空的**（#142）：读库读到 0 行、或整批被断点续投跳过。
     * 空批不能往下走——不然每个「没有新数据」的运行都会朝目标端发一份空报文。
     */
    private static final String BATCH_EMPTY = "edge.batchEmpty";
    /**
     * 这一批的失败**只逐条记**、别记「整批」那一行（#142）：扇回那一步失败时置上它。
     *
     * <p>摘要行归**出站**那一步（决策 6 的修订）：扇回失败时若也记一条 `batch:<批号>` 的行，
     * 右详情会在这两步各长一个「重发这一批」按钮，而重发只该长在出站那一步（决策 7）。</p>
     */
    private static final String BATCH_NO_SUMMARY = "edge.batchNoSummary";

    private final DataSourceMapper dataSourceMapper;
    private final WatermarkStore watermarkStore;
    private final MessageHistoryService messageHistory;
    private final DataSourceManager dataSourceManager;
    private final ReadSqlBatcher readSqlBatcher;

    /**
     * 正在攒的批次（#142）：键 = {@code 运行 + 合并动作序号}。
     *
     * <p>为什么按「运行 + 动作」而不是按批号：到齐判定发生在**批 exchange** 上（读库那批的 split
     * 收尾之后），而批 exchange 上的批号与**消息身上**的批号可能不是同一个（中间隔着一个拆分动作时，
     * 消息带的是拆分换的那个号）。同一个动作在一次运行里同时只攒一批（读库循环是一批一批走的），
     * 所以这个键就是「当前这一批」。攒完即删；运行结束（成功或失败）再按运行前缀扫一次兜底。</p>
     */
    private final Map<String, AggregateBatch> pendingBatches = new ConcurrentHashMap<>();

    public RouteGenerator(
            DataSourceMapper dataSourceMapper,
            WatermarkStore watermarkStore,
            MessageHistoryService messageHistory,
            DataSourceManager dataSourceManager,
            ReadSqlBatcher readSqlBatcher) {
        this.dataSourceMapper = dataSourceMapper;
        this.watermarkStore = watermarkStore;
        this.messageHistory = messageHistory;
        this.dataSourceManager = dataSourceManager;
        this.readSqlBatcher = readSqlBatcher;
    }

    public List<RouteBuilder> build(Job job, List<Action> actions) {
        List<Action> sorted = actions.stream()
                .sorted(Comparator.comparing(Action::getActionIndex))
                .toList();
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("任务没有动作，无法生成路由");
        }
        List<RouteBuilder> builders = new ArrayList<>();
        if (sorted.getFirst().getType() == ActionKind.MQ_CONSUME) {
            builders.add(buildConsumerEntry(job, sorted));
            builders.add(buildReplay(job, sorted));
        } else {
            builders.add(buildPollingEntry(job, sorted));
        }
        builders.add(buildResumeRoute(job, sorted));
        // 合并动作要配**打包重发**路由（#142 / ADR-0017 决策 7）：摘要行落在**出站**那一步，
        // 所以链上每个出站动作各一条——出口（数据源 / 路径 / 方法 / exchange）都是构建期解析的，归路由。
        // 没有合并动作的链不建这些（那种链上不存在「一整批」，重发按逐条重投走）。
        if (sorted.stream().anyMatch(action -> action.getType() == ActionKind.AGGREGATE)) {
            for (Action action : sorted) {
                if (action.getType() == ActionKind.HTTP_CALL || action.getType() == ActionKind.MQ_PUBLISH) {
                    // 配对配置在**扇回**那一档上（出口自己不配对），所以这里把指向这个出口的那条扇回找出来
                    FanoutConfig fanout = fanoutAfter(sorted, action.getActionIndex());
                    builders.add(buildBatchResend(job, action,
                            ActionConfig.from(action.getType(), action.getAddition()), fanout));
                }
            }
        }
        return builders;
    }

    /** 指向这个出口的扇回动作（没有就返回 {@code null}）：重发时按它配对，出口自己不配对。 */
    private static FanoutConfig fanoutAfter(List<Action> actions, int outletStep) {
        return actions.stream()
                .filter(action -> action.getType() == ActionKind.FANOUT)
                .map(action -> FanoutConfig.from(action.getAddition() == null ? Map.of() : action.getAddition()))
                .filter(fanout -> Integer.valueOf(outletStep).equals(fanout.sourceStep()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 「打包重发」路由（#142 / ADR-0017 决策 7）：把**出站那一行**存下来的整份报文，按该动作自己的出口
     * 再发一次，**一次调用 = 一次请求**。入口 {@code direct:job-<id>-batch-resend-<出站动作序号>} 由
     * {@link MessageHistoryService#redeliverBatch} 调用，带上「这一批哪几条消息、各自那一片是什么」。
     *
     * <p>HTTP 有响应：照链上那条路配对，给这一批每条各记一行；MQ 没有响应：只记这一批的结果（不配对）。
     * 失败面复用既有 {@code onException}（那时消息标识已是摘要行、批号是原批，加上成员清单就能给这一批
     * 每条补记一行 FAILED）。</p>
     */
    private RouteBuilder buildBatchResend(Job job, Action action, ActionConfig config, FanoutConfig fanout) {
        int index = action.getActionIndex();
        boolean http = config instanceof HttpCallConfig;
        String uri;
        String method = "POST";
        String auth = null;
        if (config instanceof HttpCallConfig call) {
            DataSource ds = dataSourceMapper.selectById(call.dataSourceId());
            HttpDataSourceConfig httpConfig = (HttpDataSourceConfig)
                    DataSourceConfig.from(ds.getType(), ds.getAddition());
            uri = composeUrl(ds.getAddress(), call.path());
            method = call.method() == null || call.method().isBlank() ? "POST" : call.method();
            auth = httpConfig.token() == null || httpConfig.token().isBlank() ? null : "Bearer " + httpConfig.token();
        } else {
            MqPublishConfig publish = (MqPublishConfig) config;
            String exchange = publish.exchange();
            String routingKey = publish.routingKey() == null
                    ? (publish.queue() == null ? "" : publish.queue())
                    : publish.routingKey();
            uri = "spring-rabbitmq:" + exchange + "?routingKey=" + routingKey + "&disableReplyTo=true";
        }
        String target = uri;
        String httpMethod = method;
        String authorization = auth;
        return new RouteBuilder() {
            @Override
            public void configure() {
                configureErrorHandling(this, job, false);
                from("direct:job-" + job.getId() + "-batch-resend-" + index)
                        .routeId("batch-resend-" + job.getId() + "-" + index)
                        .process(ex -> {
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            beginStep(ex); // #134：计时起点打在出站之前
                            if (http) {
                                ex.getMessage().setHeader(Exchange.HTTP_METHOD, httpMethod);
                                ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                                if (authorization != null) {
                                    ex.getMessage().setHeader("Authorization", authorization);
                                }
                            }
                        })
                        .marshal().json()
                        .to(target)
                        .process(ex -> settleBatchResend(ex, action, fanout, index));
            }
        };
    }

    /**
     * 打包重发成功之后的收尾（#142）：HTTP 按业务键配对后在新运行里记摘要行与这一批每条各一行；
     * MQ 没有响应，只记这两类行（不配对）。
     *
     * <p>配不上就抛——交给统一的记账路（摘要行 + 这一批每条都记 FAILED，同因），与链上那条路同一个口径。</p>
     */
    private void settleBatchResend(Exchange ex, Action action, FanoutConfig fanout, int index) {
        List<AggregateBatch.Carrier> carriers = ex.getProperty(BATCH_MANIFEST, List.class);
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        String batchId = ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class);
        Long durationMs = stepDuration(ex);
        Map<String, Object> paired = Map.of();
        Object response = null;
        if (fanout != null) {
            // 这个出口后面挂着扇回：响应要按业务键配回这一批每条（配对字段归扇回那一档配）
            response = HttpCallResponse.parse(responseText(ex));
            messageHistory.requireCaptureFitsSnapshot(index, response);
            Map<String, Object> matched = AggregateBatch.pair(response, fanout.resultKeyField(), carriers);
            List<String> missing = carriers.stream()
                    .filter(carrier -> !matched.containsKey(carrier.messageId()))
                    .map(carrier -> carrier.businessKey() == null ? "（这一条没有业务键）" : carrier.businessKey())
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("重发的响应里没有配对到业务键 " + missing + " 的那一条（配对字段 "
                        + fanout.resultKeyField() + "）");
            }
            paired = matched;
        }
        messageHistory.recordSuccess(runId, MessageHistoryService.SUMMARY_MESSAGE_PREFIX + batchId, index,
                action.getName(), ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class), response, null,
                attemptsOnSuccess(ex), durationMs, new BatchStamp(batchId, null, null));
        int seq = 0;
        for (AggregateBatch.Carrier carrier : carriers) {
            seq++;
            messageHistory.recordSuccess(runId, carrier.messageId(), index, action.getName(), carrier.body(),
                    paired.get(carrier.messageId()), carrier.businessKey(), attemptsOnSuccess(ex), durationMs,
                    new BatchStamp(batchId, seq, carrier.parentMessageId()));
        }
    }

    /**
     * HTTP 出站后的收尾（#123 / #126）：把响应体解析进 {@code context[本动作序号]}，再把链上消息体还原成
     * 本次请求体。
     *
     * <p><b>恒捕获</b>——契约（{@code docs/action-chain-design.md} §2）本就要求每个动作完成后写
     * {@code context[actionIndex] = 输出}，HTTP 动作的输出就是响应，所以没有开关也不新增配置项。</p>
     *
     * <p><b>还原消息体</b>是 #126：Camel HTTP 组件默认把响应装进 body，于是响应顶替了请求数据——
     * 紧随其后的 {@code WRITE_UPSERT} 在空响应（204 / 空 200）上拿到 {@code null} 取 keySet 直接 NPE，
     * 一次**成功**的调用被判成整链失败。想继续往下传响应，就显式引用本动作序号
     * （下一步 FIELD_MAP 的 {@code sourceStep}，或后续动作的取源参数）。</p>
     *
     * <p>失败面不变：4xx/5xx 由 Camel HTTP 组件抛 {@code HttpOperationFailedException}，根本走不到这里
     * （不写 context、不执行后续写回），按 ADR-0003 失败即停 + 重试退避。</p>
     */
    private void captureResponse(Exchange ex, Action action, int index) {
        Object request = ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class);
        String raw = responseText(ex);
        // 捕获内容不做截断：装不下就当场失败（可读错误），绝不写半截 JSON（#123 口径 4；列容量本身是 #141=MEDIUMTEXT）
        messageHistory.requireCaptureFitsSnapshot(index, raw);
        Map<Integer, Object> context = context(ex);
        context.put(index, HttpCallResponse.parse(raw));
        ex.setProperty(CONTEXT, context);
        ex.getMessage().setBody(request);
        // data_snapshot 语义不变（input = 请求、data = 响应）：记响应原文，不记解析后的对象
        recordSuccessStep(ex, action, request, raw);
    }

    /**
     * 响应体的原始文本。走 Camel 的类型转换（InputStream / StreamCache / byte[] 都能读），
     * 读不出来就当场失败——不留一个"看起来成功、其实没捕获到"的步骤。
     */
    private String responseText(Exchange ex) {
        Object body = ex.getMessage().getBody();
        if (body == null) {
            return null;
        }
        try {
            return ex.getContext().getTypeConverter().mandatoryConvertTo(String.class, ex, body);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 响应体读不出文本，无法捕获: " + e.getMessage(), e);
        }
    }

    /** 直连入口：仅构建 direct（手动/外部/自动调度共用一个执行入口）；自动触发由 JobScheduler 依调度配置投递。 */
    private RouteBuilder buildPollingEntry(Job job, List<Action> actions) {
        String routeId = "job-" + job.getId();
        return new RouteBuilder() {
            @Override
            public void configure() {
                configureErrorHandling(this, job, false);
                RouteDefinition route = from("direct:" + routeId).routeId(routeId);
                route.process(ex -> beginRun(ex, job));
                ProcessorDefinition<?> chain = appendChain(route, actions, job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /**
     * 消费入口：MQ 消费主路由（MQ_CONSUME 为入口）。exchange 名/类型/binding key 取动作侧配置（ADR-0010），
     * 缺省回退：exchange=数据源名、exchangeType=direct、routingKey=queue（存量任务零影响）。
     */
    private RouteBuilder buildConsumerEntry(Job job, List<Action> actions) {
        String routeId = "job-" + job.getId();
        MqConsumeConfig consume = (MqConsumeConfig) ActionConfig.from(
                ActionKind.MQ_CONSUME, actions.getFirst().getAddition());
        DataSource mqDs = dataSourceMapper.selectById(consume.dataSourceId());
        String endpoint = "spring-rabbitmq:" + consume.effectiveExchange(mqDs.getName())
                + "?exchangeType=" + consume.effectiveExchangeType()
                + "&queues=" + consume.queue()
                + "&routingKey=" + consume.effectiveRoutingKey(consume.queue())
                + "&autoDeclare=true";
        return new RouteBuilder() {
            @Override
            public void configure() {
                configureErrorHandling(this, job, true);
                ProcessorDefinition<?> route = from(endpoint)
                        .routeId(routeId)
                        .unmarshal().json()
                        .process(ex -> {
                            Map<Integer, Object> context = context(ex);
                            context.put(actions.getFirst().getActionIndex(), ex.getMessage().getBody());
                            ex.setProperty(CONTEXT, context);
                            beginRun(ex, job);
                            beginMessage(ex, consume.businessKey());
                        });
                ProcessorDefinition<?> chain = appendChain(route, actions.subList(1, actions.size()), job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /** 重放路由：direct 入口接收已解析输入（手动执行/断点重放）。 */
    private RouteBuilder buildReplay(Job job, List<Action> actions) {
        MqConsumeConfig consume = (MqConsumeConfig) ActionConfig.from(
                ActionKind.MQ_CONSUME, actions.getFirst().getAddition());
        return new RouteBuilder() {
            @Override
            public void configure() {
                configureErrorHandling(this, job, false);
                RouteDefinition route = from("direct:job-" + job.getId())
                        .routeId("direct-" + job.getId())
                        .process(ex -> {
                            Map<Integer, Object> context = context(ex);
                            context.put(actions.getFirst().getActionIndex(), ex.getMessage().getBody());
                            ex.setProperty(CONTEXT, context);
                            beginRun(ex, job);
                            beginMessage(ex, consume.businessKey());
                        });
                ProcessorDefinition<?> chain = appendChain(route, actions.subList(1, actions.size()), job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /**
     * 断点续跑路由：direct 入口接收失败步数据，从 {@code edge.resumeFromStep} 动作起重放。
     *
     * <p>重投入口随 exchange 带上 {@code edge.resumeContext}（同一消息更早成功步骤的 data_snapshot，#90），
     * 在此回填 context：被跳过的上游步骤因此仍可被下游规则引用（如 FIELD_MAP 的 {@code sourceStep}），
     * 不再静默映射出空。{@code context[0]} 保持既有语义——本次重投的 body（该步 inputSnapshot）。</p>
     */
    private RouteBuilder buildResumeRoute(Job job, List<Action> actions) {
        String routeId = "resume-" + job.getId();
        return new RouteBuilder() {
            @Override
            public void configure() {
                configureErrorHandling(this, job, false);
                RouteDefinition route = from("direct:job-" + job.getId() + "-resume")
                        .routeId(routeId)
                        .process(ex -> {
                            // context(ex) 已把 context[0] 置为本次重投 body（= 失败步 inputSnapshot），此处只回填上游步骤
                            Map<Integer, Object> next = context(ex);
                            Object upstreamContext = ex.getProperty(MessageHistoryService.RESUME_CONTEXT, Object.class);
                            if (upstreamContext instanceof Map<?, ?> upstream) {
                                upstream.forEach((step, data) -> {
                                    // 只回填动作序号 > 0 的步骤；0 号槽位是本次重投的输入，不覆盖
                                    if (step instanceof Integer index && index > 0) {
                                        next.put(index, data);
                                    }
                                });
                            }
                            ex.setProperty(CONTEXT, next);
                            // Camel 的 split/choice 片段之间的 body 约定：链上"当前数据"就是 context[0]
                            ex.getMessage().setBody(next.get(0));
                            beginRun(ex, job);
                            beginMessage(ex, null); // 业务键已由重投入口写入，直接沿用
                        });
                ProcessorDefinition<?> chain = appendChain(route, actions.subList(1, actions.size()), job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /** 按动作种类逐段追加片段；READ_SQL 触发 split，其后的片段在行内执行。返回追加后的链节点。 */
    private ProcessorDefinition<?> appendChain(ProcessorDefinition<?> route, List<Action> actions, Job job) {
        ProcessorDefinition<?> current = route;
        int openScopes = 0;
        boolean inSplit = false;
        boolean inBatchLoop = false;
        for (Action action : actions) {
            ActionKind kind = action.getType();
            ActionConfig config = ActionConfig.from(kind, action.getAddition());
            if (kind == ActionKind.READ_SQL && !inSplit) {
                ReadSqlConfig read = (ReadSqlConfig) config;
                // 确保动作选定的客户库连接池已建立，路由启动才能按名解析/构造 JdbcTemplate
                customerDataSource(read.dataSourceId());
                boolean hasWatermark = read.watermarkColumn() != null && !read.watermarkColumn().isBlank();
                if (hasWatermark) {
                    // #79 分批 + keyset 分页：读水位 → 循环（读一批 → split 行 → 整批成功才推进水位）→ 直到扫完
                    LoopDefinition loop = current
                            .process(ex -> prepareRead(ex, job, read, action.getActionIndex(), action.getName()))
                            .loopDoWhile(this::moreBatches);
                    current = loop.process(ex -> readBatch(ex, job, read))
                            .split(ExpressionBuilder.bodyExpression());
                    inBatchLoop = true;
                } else {
                    // 无水位列：全量单次读（不分批、不推进水位），保留原语义
                    String sql = read.sql();
                    current = current
                            .process(ex -> {
                                ex.setProperty(MessageHistoryService.ACTION_INDEX, action.getActionIndex());
                                ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                                // 不分批那条路：一次读 = 一批，所以批号打在这里（#139）
                                ex.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
                                beginStep(ex); // #134：计时起点打在读库之前
                            })
                            .to("sql:" + sql + "?dataSource=#edge-ds-" + read.dataSourceId() + "&outputType=SelectList")
                            // 读完了就结算（#134）：落库发生在每条源行上，而读只做了一次。
                            // 在 split 之前结算，各源行共用这一个数；不然第二条源行量到的是「读库 + 前一条跑完」
                            .process(this::settleStepDuration)
                            .split(ExpressionBuilder.bodyExpression());
                }
                openScopes++;
                inSplit = true;
                // 源行为"消息创建"时机：每条源行 = 一条消息，在 split 内首部创建消息标识与业务键
                current = current.process(ex -> beginMessage(ex, read.businessKey()));
                // 读取动作本身也作为链上一步：每条源行记一条 SUCCESS 消息步（无输入快照，data=该行）
                current = current.process(ex -> recordSuccessStep(ex, action, null));
            }
            if (kind == ActionKind.SPLIT) {
                current = appendSplit(current, action, (SplitConfig) config);
                // 这一层的 end() 由收尾那段按层数完成：这里已进 split 体，其后的动作片段照旧逐条追加，
                // 处理的是**每条子消息**
                openScopes++;
            }
            if (kind == ActionKind.AGGREGATE) {
                // 合并（#142 / ADR-0017 决策 6 的修订版）分两半，位置由「攒齐的边界 = 上游读取批读完那一刻」定：
                //  ① 前半段留在**当前这一层**（读库/拆分那几层扇出之内）——每条消息各来一次，记一张承运单；
                //  ② 然后把扇出那几层收回来（= 这一批读完了），在**批 exchange** 上打成一条报文。
                // 打完**不开新扇出**：链上从这里起只有一条消息，后面的出站动作（HTTP_CALL / MQ_PUBLISH）
                // 天然只发一次；响应由扇回（FANOUT）那一档摊回原来那 N 条。
                //
                // 空批（读库读到 0 行 / 整批被续投跳过）用一层 filter 兜住：不然**每个「没有新数据」的运行**
                // 都会朝目标端发一份空报文。这一层与扇回那层一样按 openScopes 记账、收尾时统一退回来。
                current = appendAggregateCollect(current, action);
                while (openScopes > 0) {
                    current = current.end();
                    openScopes--;
                }
                current = appendAggregatePack(current, action, (AggregateConfig) config);
                current = current.filter(ex -> !Boolean.TRUE.equals(ex.getProperty(BATCH_EMPTY, Boolean.class)));
                openScopes = 1;
            }
            if (kind == ActionKind.FANOUT) {
                // 扇回（#142）：1 → N，**回到原来那几条消息**（身份从成员清单里来），不换批号
                current = appendFanout(current, action, (FanoutConfig) config);
                openScopes++;
            }
            current = appendFragment(current, action, config, job);
        }
        // 收尾按**嵌套的由内到外**顺序关闭。一条链上可能叠着多层：读库分批那层（loopDoWhile + split）、
        // 拆分那层（split）、合并之后那层（filter，空批兜底）与扇回那层（split）。
        //
        // 两个坑：① 每开一层就要 end() 一次，而且必须**由内到外**（先关扇回/拆分那些、再交水位、
        // 最后关循环），否则水位处理器会被追加到内层里、每行提交一次；② Camel 的 `end()` 在**循环体内**
        // 只往外退一层，嵌套时要按开过的层数退够（所以这里调 ProcessorDefinition.end()，不假设是哪一种定义）。
        while (openScopes > 0) {
            current = current.end();
            openScopes--;
        }
        if (inBatchLoop) {
            // 一批的行处理完（含其中每条子消息走完拆分之后的步骤），才提交该批水位；
            // 批失败时不会走到这一步，从而实现"批失败只丢该批"
            current = current.process(ex -> updateWatermark(ex, job));
            current = ((LoopDefinition) current).end();
        }
        return current;
    }

    /**
     * 合并动作的**前半段**（#142）：把这条消息记成一张承运单，交进「这一批」的护栏。
     *
     * <p>它必须落在**当前这一层**（读库/拆分那几层扇出之内）：每条消息各来一次，攒齐才发。断点续投
     * 跳过这一步时照样记单（标 {@code skipped}）——那一条不参与发送，但现场要还回去，它之后的步骤照跑
     * （下游引用合并那一步的输出从 {@code edge.resumeContext} 拿）。</p>
     */
    private ProcessorDefinition<?> appendAggregateCollect(ProcessorDefinition<?> current, Action action) {
        int index = action.getActionIndex();
        return current.process(ex -> {
            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
            ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
            collectForAggregate(ex, index);
        });
    }

    /**
     * 合并动作的**后半段**（#142 / ADR-0017 决策 6 的修订版）：在批 exchange 上把这一批**打成一条报文**。
     *
     * <p>它**不认识出口**：打包完链上只有一条消息，后面的出站动作（{@code HTTP_CALL} / {@code MQ_PUBLISH}）
     * 因此天然只发一次；出口的种类与数量由链决定，换出口不用动合并配置。响应由扇回（{@code FANOUT}）
     * 那一档摊回原来那 N 条。</p>
     *
     * <p>空批（读库读到 0 行 / 整批被续投跳过）标 {@link #BATCH_EMPTY}：调用方用一层 filter 兜住，
     * 后续动作（含出站）一律不跑——不然**每个「没有新数据」的运行**都会朝目标端发一份空报文。</p>
     */
    private ProcessorDefinition<?> appendAggregatePack(ProcessorDefinition<?> current, Action action,
                                                       AggregateConfig aggregate) {
        int index = action.getActionIndex();
        return current.process(ex -> {
            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
            beginStep(ex); // #134：计时起点打在打包之前
            List<AggregateBatch.Carrier> carriers = takeBatch(ex, index);
            if (carriers.isEmpty() || carriers.stream().allMatch(AggregateBatch.Carrier::skipped)) {
                // 空批 / 整批被断点续投跳过：这一趟没有要发的东西，后面那层 filter 会兜住
                ex.setProperty(BATCH_EMPTY, true);
                return;
            }
            Object wholeBatch = carriers.stream().map(AggregateBatch.Carrier::body).toList();
            Map<String, Object> packed = new LinkedHashMap<>();
            packed.put(aggregate.bodyField(), wholeBatch);
            // 容量闸（#123 口径 4 / #141 的列容量）：整批报文装不下就当场失败、绝不写半截
            messageHistory.requireCaptureFitsSnapshot(index, packed);
            ex.setProperty(MessageHistoryService.STEP_INPUT, wholeBatch);
            ex.getMessage().setBody(packed);
            context(ex).put(index, packed);
            ex.setProperty(BATCH_MANIFEST, carriers);
            // 这一行是「整批 → 打包报文」：输入是整批（每条各自那一片），输出是打包出来的那一条报文。
            // **整份请求 / 整份响应**那一行（摘要行）归**出站**动作记（决策 6 的修订）
            recordSuccessStep(ex, action, wholeBatch, packed);
        });
    }

    /** 攒批键：{@code 运行 + 合并动作序号}（同一次运行里，一个合并动作同时只攒一批，见那个字段的说明）。 */
    private static String batchKey(Long runId, int index) {
        return runId + ":" + index;
    }

    /**
     * 把这条消息记成一张承运单（#142）。没有运行标识（还没 beginRun）时什么都不做——那种 exchange
     * 也不会走到出站那一步。
     */
    private void collectForAggregate(Exchange ex, int index) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        if (runId == null) {
            return;
        }
        String messageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
        String parentMessageId = ex.getProperty(MessageHistoryService.PARENT_MESSAGE_ID, String.class);
        Integer shares = ex.getProperty(BATCH_EXPECTED, Integer.class);
        pendingBatches.computeIfAbsent(batchKey(runId, index), key -> new AggregateBatch())
                .add(new AggregateBatch.Carrier(
                        messageId,
                        ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class),
                        ex.getProperty(MessageHistoryService.BATCH_SEQ, Integer.class),
                        parentMessageId,
                        ex.getMessage().getBody(),
                        // 上下文**拷一份**：split 出来的兄弟 exchange 可能共用同一个 Map，现场不能被别人改掉
                        new LinkedHashMap<>(context(ex)),
                        parentMessageId != null ? parentMessageId : messageId,
                        shares == null ? 1 : shares,
                        isResumeSkipped(ex, index)));
    }

    /**
     * 判齐 + 转批号 + 把这一批的成员清单交给打包那一步（#142）。
     *
     * <p>三种情形：**这一批没产出消息**（读库读到空批）→ 返回空的清单；**整批被断点续投跳过** →
     * 照样返回（调用方按 {@code skipped} 判断，不发也不记）；**齐了** → 现生成新批号（合并是基数变化动作，
     * ADR-0017 决策 2/3 的转手）、给打包出来那一条消息一个**自己的消息标识**，返回成员清单。</p>
     *
     * <p>凑不满（上游有条消息没走到这儿）当场失败：**绝不静默发半批**（决策 8）。到这一步说明整次运行
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
     * 扇回动作的片段（#142）：把出站拿回来的响应按配对字段**摊回原来那 N 条消息**（1 → N）。
     *
     * <p>与拆分那一档同一套记账：这一层是 split，后续动作追加在它里面逐条跑，收尾时按层数统一退回。
     * 区别在**身份**：拆分会新造消息、换批号；扇回沿用成员清单里那几条（消息标识 / 业务键 / 父消息标识 /
     * 批内序号），**也不换批号**——它是把这一批还原回成员，不是新的一批。</p>
     */
    private ProcessorDefinition<?> appendFanout(ProcessorDefinition<?> current, Action action, FanoutConfig fanout) {
        int index = action.getActionIndex();
        return current
                .process(ex -> {
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    beginStep(ex); // #134：计时起点打在配对之前
                    prepareFanout(ex, action, fanout, index);
                })
                .split(ExpressionBuilder.bodyExpression())
                    .process(this::restoreCarrier);
    }

    /**
     * 配对 + 给这一批每条各记一行（#142）：输入是**自己那一片请求**、输出是**配给自己的那一份响应**，
     * 批内序号沿用攒批顺序。
     *
     * <p>**同成同败**：有一条没配上（响应里没有它的业务键）就算整批失败——先给这一批每条记一行 FAILED
     * （同因，各自带自己那一片），再抛出去让这一批失败，绝不留「看着成功其实只回了一部分」的行。</p>
     */
    private void prepareFanout(Exchange ex, Action action, FanoutConfig fanout, int index) {
        List<AggregateBatch.Carrier> carriers = ex.getProperty(BATCH_MANIFEST, List.class);
        if (carriers == null || carriers.isEmpty()) {
            throw new IllegalStateException(
                    "扇回这一步的上游没有合并动作（没有成员清单）：扇回只能接在「合并 + 出站」之后");
        }
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        String batchId = ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class);
        Object source = context(ex).get(fanout.sourceStep());
        Object response = ElementMessageFactory.valueAt(source, fanout.sourcePath());
        if (response == null) {
            throw new IllegalStateException("扇回取不到回应：sourceStep=" + fanout.sourceStep()
                    + (fanout.sourcePath() == null || fanout.sourcePath().isBlank() ? "" : "（路径 " + fanout.sourcePath() + "）"));
        }
        messageHistory.requireCaptureFitsSnapshot(index, response);
        Long durationMs = stepDuration(ex);
        Map<String, Object> paired = AggregateBatch.pair(response, fanout.resultKeyField(), carriers);
        List<String> missing = carriers.stream()
                .filter(carrier -> !paired.containsKey(carrier.messageId()))
                .map(carrier -> carrier.businessKey() == null ? "（这一条没有业务键）" : carrier.businessKey())
                .toList();
        if (!missing.isEmpty()) {
            // 这一步的失败**只逐条记**（交给统一的失败那条路：它按成员清单给这一批每条记一行 FAILED）——
            // 不记「整批」那一行，否则右详情会在扇回这一步长出一个「重发这一批」按钮，
            // 而重发只该长在**出站**那一步（#142 / 决策 7）
            ex.setProperty(BATCH_NO_SUMMARY, true);
            throw new IllegalStateException("扇回的响应里没有配对到业务键 " + missing + " 的那一条（配对字段 "
                    + fanout.resultKeyField() + "）");
        }
        List<AggregateBatch.Carrier> settled = new ArrayList<>();
        int seq = 0;
        for (AggregateBatch.Carrier carrier : carriers) {
            seq++;
            Object slice = paired.get(carrier.messageId());
            messageHistory.recordSuccess(runId, carrier.messageId(), index, action.getName(), carrier.body(),
                    slice, carrier.businessKey(), attemptsOnSuccess(ex), durationMs,
                    new BatchStamp(batchId, seq, carrier.parentMessageId()));
            settled.add(carrier.settled(seq, fanout.attachAs(), slice, index));
        }
        ex.getMessage().setBody(settled);
    }

    /** 扇回：把成员清单里那一条的现场装回 exchange，后续动作照旧逐条跑（#142）。 */
    private void restoreCarrier(Exchange ex) {
        AggregateBatch.Carrier carrier = ex.getMessage().getBody(AggregateBatch.Carrier.class);
        ex.setProperty(MessageHistoryService.MESSAGE_ID, carrier.messageId());
        ex.setProperty(MessageHistoryService.BUSINESS_KEY, carrier.businessKey());
        ex.setProperty(MessageHistoryService.BATCH_SEQ, carrier.batchSeq());
        ex.setProperty(MessageHistoryService.PARENT_MESSAGE_ID, carrier.parentMessageId());
        ex.setProperty(MessageHistoryService.STEP_INPUT, carrier.body());
        ex.setProperty(CONTEXT, new LinkedHashMap<>(carrier.context()));
        ex.getMessage().setBody(carrier.body());
    }

    /**
     * 认出「这一条是合并打出来的整批载体」（#142）：出站动作（{@code HTTP_CALL} / {@code MQ_PUBLISH}）
     * 执行前调它，把这一行的消息标识换成**摘要行**的 {@code batch:<批号>}——整份请求与整份响应因此
     * 只落在这一行上（ADR-0017 决策 6 的修订：摘要行归**出站**那一步，不再归合并）。
     *
     * <p>没有成员清单（链上没合并动作）时什么都不做：普通出站动作照旧记自己那条消息的行。</p>
     */
    private void markBatchCarrier(Exchange ex) {
        if (ex.getProperty(BATCH_MANIFEST) == null) {
            return;
        }
        String batchId = ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class);
        if (batchId != null) {
            ex.setProperty(MessageHistoryService.MESSAGE_ID,
                    MessageHistoryService.SUMMARY_MESSAGE_PREFIX + batchId);
        }
    }

    /** 运行结束（成功或失败）时把这次运行还挂着的攒批清掉（#142）：半批留下的现场不该跟着实例过夜。 */
    private void clearPendingBatches(Long runId) {
        if (runId != null) {
            pendingBatches.keySet().removeIf(key -> key.startsWith(runId + ":"));
        }
    }

    /**
     * 拆分动作的片段（#140 / ADR-0017 决策 4）：**自己记一行**消息步 + 把数组摊成 N 条子消息。
     *
     * <p>三件事按顺序发生：</p>
     * <ol>
     *   <li><b>解析来源</b>：{@code sourceStep} 空 = 当前消息体，否则 {@code context[sourceStep]}；
     *       再按 {@code sourcePath} 走到那片数组。解析不出来（步骤没执行 / 路径断了 / 不是数组）
     *       当场显式失败——绝不静默拆出 0 条。</li>
     *   <li><b>记一行消息步</b>：输入是**父消息**、输出是**子消息们**（那片数组），挂父消息自己的
     *       消息标识；这一行带的是**上游那个批号**（批号在转手之前还属于上一段）。</li>
     *   <li><b>换号并摊开</b>：现生成一个新批号 + 记住父消息标识，再按分辨率出来的那片数组 split；
     *       两格都写在进入 split 之前，于是每条子消息**继承**新批号、并记得自己从哪条消息拆出来。</li>
     * </ol>
     *
     * <p>拆完之后链上仍是「一条一条走」：其后的片段逐个处理每条子消息，失败即停 / 重试退避 /
     * 水位推进这些既有语义一个字不改。</p>
     */
    private ProcessorDefinition<?> appendSplit(ProcessorDefinition<?> current, Action action, SplitConfig split) {
        int index = action.getActionIndex();
        return current
                .process(ex -> {
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    beginStep(ex); // #134：计时起点打在解析与摊开之前
                    Object input = ex.getMessage().getBody();
                    Object source = resolveSplitSource(ex, split);
                    List<?> elements = splitArray(source, split);
                    ex.setProperty(MessageHistoryService.STEP_INPUT, input);
                    // 这一批的两样东西各挂一个属性，都是给 split 之内那条子消息用的：
                    //   source —— keepParent 摊平的是**父消息**（按配置 sourceStep + sourcePath 取到的
                    //              那个对象，不是数组里的某个元素）的外层字段；
                    //   elements —— "这一步拆出了几条"的现场（批内序号不靠它，那用 split 的迭代下标）。
                    // 不复用 edge.stepInput：它是"这一步的输入快照"，语义不同（那一格记的是父消息本身）
                    ex.setProperty(SPLIT_SOURCE, source);
                    // 这一行本该摊出几条（#142）：合并动作判「齐了没有」要用它；同一行拆出来的 k 条
                    // 带的是同一个数（去重靠父消息标识），写在进 split 之前——子 exchange 继承它
                    ex.setProperty(BATCH_EXPECTED, elements.size());
                    // 这一行的输入是父消息（就是当下的消息体）、输出是子消息们（那片数组），
                    // 挂的是**父消息自己的标识**（它自己那一行，谱系上就是"父"），带的是**上游那个批号**
                    // （批号还没转手，这一段仍属上一批；MQ 入口那种没有上游批号的链，这一格就是空）。
                    // 所以它要在换号之前记；data 显式给 elements（而不是让默认那条路取 body）：
                    // 这一行的输出是拆出来的那些子消息，不是父消息自己
                    String parentMessageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
                    ex.setProperty(MessageHistoryService.PARENT_MESSAGE_ID, parentMessageId);
                    recordSuccessStep(ex, action, input, elements);
                    // 批号在这里**换手**（ADR-0017 决策 2/3）：新号给这一批子消息，父消息标识继续往下传
                    // 记谱系。两条都写在进入 split 之前——子 exchange 继承它们（与 READ_SQL 那条路同一手法）
                    ex.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
                    ex.setProperty(MessageHistoryService.PARENT_MESSAGE_ID, parentMessageId);
                    // 父消息标识已经记下来了；从那以后这一格不再代表"当前消息"（子消息各自有自己的标识），
                    // 留着会让「失败步挂哪条消息」挂错
                    ex.removeProperty(MessageHistoryService.MESSAGE_ID);
                })
                // **不用 body()**：Camel 的 split(body()) 遇到 Map 会按 entry 摊开（每条子消息拿到一个
                // Map.Entry），而拆分这一步的消息体既可能是数组、也可能是装着数组的报文。这里显式按
                // 「来源步骤 + 路径」现解析那片数组，与上面记行用的是同一个解析（同一份配置、同一个答案）。
                // 写成匿名类而不是 lambda：Expression 的 evaluate 带自己的类型参数，不是函数式接口
                .split(new Expression() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T> T evaluate(Exchange ex, Class<T> type) {
                        return (T) splitArray(resolveSplitSource(ex, split), split);
                    }
                })
                .process(ex -> beginChildMessage(ex, split));
    }

    /**
     * 拆分**来源对象**：{@code sourceStep} 空取当前消息体、否则取 {@code context[sourceStep]}。
     *
     * <p>它是两件事的共同出处：按 {@code sourcePath} 走进去得到那片数组，以及 {@code keepParent}
     * 要摊平的**父消息外层字段**（父消息就是它，不是数组里的某个元素）。</p>
     *
     * <p>取不到就**当场显式失败**：步骤没执行（被断点跳过）或当前消息体为空——两种都是配置或链的
     * 问题，静默拆出 0 条只会让运行"成功"而一条数据都不落库。</p>
     */
    private Object resolveSplitSource(Exchange ex, SplitConfig split) {
        Object source = split.sourceStep() == null
                ? ex.getMessage().getBody()
                : context(ex).get(split.sourceStep());
        if (source == null) {
            throw new IllegalStateException("拆分的来源没有数据"
                    + (split.sourceStep() == null ? "（当前消息体为空）"
                    : "（sourceStep=" + split.sourceStep() + " 未执行或被断点跳过）"));
        }
        return source;
    }

    /**
     * 来源里那片数组（{@code sourcePath} 走进去；空路径 = 来源本身就是数组）。取不到、或取到的不是
     * 数组，同样当场显式失败——理由见 {@link #resolveSplitSource}。
     */
    private static List<?> splitArray(Object source, SplitConfig split) {
        String where = split.sourcePath() == null || split.sourcePath().isBlank()
                ? "（来源本身）" : split.sourcePath();
        Object array = ElementMessageFactory.valueAt(source, split.sourcePath());
        if (array == null) {
            throw new IllegalStateException("拆分的来源路径取不到数据: " + where);
        }
        if (!(array instanceof List<?> list)) {
            throw new IllegalStateException(
                    "拆分的来源不是数组（是 " + array.getClass().getSimpleName() + "）: " + where);
        }
        return list;
    }

    /**
     * 一条子消息的初始化（拆分那一档）：消息标识现生成、业务键取自元素里的配置路径、批内序号按
     * 元素在数组里的顺序（从 1 起）、父消息标识与批号继承父 exchange 上那两格。
     *
     * <p>{@code keepParent} 摊平的是**父消息**（{@link #SPLIT_SOURCE}，也就是按配置取到的那个对象）
     * 的外层字段，不是数组里某个元素的；而且它**只影响 body**——父消息标识照记，所以
     * 「改 keepParent 不影响谱系」。</p>
     */
    private void beginChildMessage(Exchange ex, SplitConfig split) {
        Object element = ex.getMessage().getBody();
        Object parentBody = ex.getProperty(SPLIT_SOURCE, Object.class);
        String parentMessageId = ex.getProperty(MessageHistoryService.PARENT_MESSAGE_ID, String.class);
        // 批内序号 = 元素在数组里的位置，**用 split 自己的迭代下标**（从 0 起，+1 成"第几条"）。
        // 不按值回头查元素的位置：同一个元素在数组里出现两次时会查回第一个，两条子消息都算"第 1 条"
        Integer splitIndex = ex.getProperty(Exchange.SPLIT_INDEX, Integer.class);
        int position = splitIndex == null ? 1 : splitIndex + 1;
        ElementMessageFactory.InitializedMessage child = ElementMessageFactory.splitChild(
                element, split.elementKey(), parentMessageId, parentBody, split.keepParent(), position);
        ex.setProperty(MessageHistoryService.MESSAGE_ID, child.messageId());
        ex.setProperty(MessageHistoryService.BUSINESS_KEY, child.businessKey());
        // 批内序号在 split **之内**再写一次：Camel 的 split 拷进子 exchange 的属性是有选择的
        // （批号与父消息标识能过来，序号过不来），写在里面才是每条子消息确定拿到的那一次
        ex.setProperty(MessageHistoryService.BATCH_SEQ, child.batchSeq());
        // 链上"当前数据"就是这条子消息的 body（外层字段按配置摊平过了）
        ex.getMessage().setBody(child.body());
        // 拆分那一步的输出：拆之前是整片数组，拆之后**每条子消息看到的自己那一片**——
        // 下游 FIELD_MAP 用 sourceStep=拆分序号 时取到的就是自己那个元素
        Map<Integer, Object> context = context(ex);
        context.put(ex.getProperty(MessageHistoryService.ACTION_INDEX, Integer.class), child.body());
        ex.setProperty(CONTEXT, context);
    }

    /** 单个动作的片段；每个片段记录一条消息步（成功），支持断点续跑时跳过。 */
    private ProcessorDefinition<?> appendFragment(
            ProcessorDefinition<?> current,
            Action action,
            ActionConfig config,
            Job job) {
        int index = action.getActionIndex();
        return switch (action.getType()) {
            case FIELD_MAP -> {
                FieldMappingConfig mapping = (FieldMappingConfig) config;
                // DS-10.2 S1：结构化规则编译为 JSONata 表达式（路由构建时编译一次），执行器跨消息复用
                JsonataFieldMapper fieldMapper = "EXPRESSION".equals(mapping.mode())
                        ? JsonataFieldMapper.fromExpression(mapping.expression())
                        : new JsonataFieldMapper(mapping.rules());
                yield current.process(ex -> {
                    if (isResumeSkipped(ex, index)) {
                        return;
                    }
                    beginStep(ex); // #134：计时起点打在动作执行之前
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    Object input = ex.getMessage().getBody();
                    ex.setProperty(MessageHistoryService.STEP_INPUT, input);
                    Map<Integer, Object> context = context(ex);
                    ex.getMessage().setBody(fieldMapper.apply(context));
                    context.put(index, ex.getMessage().getBody());
                    ex.setProperty(CONTEXT, context);
                    recordSuccessStep(ex, action, input);
                });
            }
            case WRITE_UPSERT -> {
                WriteUpsertConfig write = (WriteUpsertConfig) config;
                JdbcTemplate writeJdbc = new JdbcTemplate(customerDataSource(write.dataSourceId()));
                // 方言在构建期定一次（不是每条消息重算）：库类型决定 upsert 写法（ADR-0014）
                SqlDialect writeDialect = requireDialect(write.dataSourceId());
                yield current.process(ex -> {
                    if (isResumeSkipped(ex, index)) {
                        return;
                    }
                    beginStep(ex); // #134：计时起点打在动作执行之前
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    Object input = ex.getMessage().getBody();
                    ex.setProperty(MessageHistoryService.STEP_INPUT, input);
                    upsert(ex, writeJdbc, write.tableName(), write.idempotencyKey(), writeDialect);
                    recordSuccessStep(ex, action, input);
                });
            }
            case HTTP_CALL -> {
                HttpCallConfig call = (HttpCallConfig) config;
                DataSource ds = dataSourceMapper.selectById(call.dataSourceId());
                HttpDataSourceConfig http = (HttpDataSourceConfig)
                        DataSourceConfig.from(ds.getType(), ds.getAddition());
                String url = composeUrl(ds.getAddress(), call.path());
                String method = call.method() == null || call.method().isBlank() ? "POST" : call.method();
                String auth = http.token() == null || http.token().isBlank() ? null : "Bearer " + http.token();
                yield current
                        .process(ex -> {
                            if (isResumeSkipped(ex, index)) {
                                ex.setProperty(SKIP_FLAG, true);
                                return;
                            }
                            ex.setProperty(SKIP_FLAG, false);
                            beginStep(ex); // #134：计时起点打在动作执行之前（含出站与响应捕获）
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
                            markBatchCarrier(ex); // 合并打出来的整批载体：这一步的那一行是**摘要行**（#142）
                            // 出站前先把 context[0] 落在**请求体**上：context() 是懒建的（拿当时的 body 当任务输入），
                            // 等出站后再建就把响应当成了任务输入。链上已有 context 时这里是空操作（#123）
                            context(ex);
                            ex.getMessage().setHeader(Exchange.HTTP_METHOD, method);
                            ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                            if (auth != null) {
                                ex.getMessage().setHeader("Authorization", auth);
                            }
                        })
                        .choice()
                            .when(ex -> !Boolean.TRUE.equals(ex.getProperty(SKIP_FLAG, Boolean.class)))
                                .marshal().json()
                                .to(url)
                                .process(ex -> captureResponse(ex, action, index))
                        .endChoice()
                        .end();
            }
            case MQ_PUBLISH -> {
                MqPublishConfig publish = (MqPublishConfig) config;
                String exchange = publish.exchange();
                String routingKey = publish.routingKey() == null
                        ? (publish.queue() == null ? "" : publish.queue())
                        : publish.routingKey();
                yield current
                        .process(ex -> {
                            if (isResumeSkipped(ex, index)) {
                                ex.setProperty(SKIP_FLAG, true);
                                return;
                            }
                            ex.setProperty(SKIP_FLAG, false);
                            beginStep(ex); // #134：计时起点打在动作执行之前（含序列化与投递）
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
                            markBatchCarrier(ex); // 合并打出来的整批载体：这一步的那一行是**摘要行**（#142）
                            // bodySourceStep 必填（MqPublishConfig.validate）：显式声明发布哪一步的输出
                            Map<Integer, Object> context = context(ex);
                            Integer step = publish.bodySourceStep();
                            if (step == null) {
                                throw new IllegalStateException(
                                        "MQ 发布动作缺少 bodySourceStep，请先补配（如 FIELD_MAP 的序号）");
                            }
                            Object source = context.get(step);
                            if (source == null) {
                                // 显式失败而非静默：断点重投跳过来源步骤时绝不发旧 body
                                throw new IllegalStateException(
                                        "bodySourceStep=" + step + " 无输出（步骤未执行或被断点跳过），已中止发布");
                            }
                            ex.getMessage().setBody(source);
                        })
                        .choice()
                            .when(ex -> !Boolean.TRUE.equals(ex.getProperty(SKIP_FLAG, Boolean.class)))
                                .marshal().json()
                                .to("spring-rabbitmq:" + exchange + "?routingKey=" + routingKey + "&disableReplyTo=true")
                                .process(ex -> recordSuccessStep(ex, action,
                                        ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class)))
                        .endChoice()
                        .end();
            }
            case READ_SQL -> current.process(ex -> {
                ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                Map<Integer, Object> context = context(ex);
                context.put(index, ex.getMessage().getBody());
                ex.setProperty(CONTEXT, context);
            });
            case MQ_CONSUME -> current.process(ex -> {
                ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                Map<Integer, Object> context = context(ex);
                context.put(index, ex.getMessage().getBody());
                ex.setProperty(CONTEXT, context);
            });
            // 拆分 / 合并 / 扇回这三档的片段整个在 appendSplit / appendChain / appendFanout 里，
            // 这里没有可加的
            case SPLIT, AGGREGATE, FANOUT -> current;
        };
    }

    private void configureErrorHandling(RouteBuilder builder, Job job, boolean handled) {
        int retries = job.getRetryCount() == null ? 0 : job.getRetryCount();
        long backoff = job.getBackoffMs() == null ? 1000L : job.getBackoffMs();
        builder.onException(Exception.class)
                .maximumRedeliveries(retries)
                .redeliveryDelay(backoff)
                // 每次投递失败都 +1（含最后一次）：计数写在自己的 exchange property 上。
                // 不读 Camel 的 CamelRedeliveryCounter——它写在 message header 上，每次重投会被
                // remove/decrement，读它恒为 null + 1（#109）。
                .onExceptionOccurred(this::noteAttemptFailure)
                // 退避等待之后的这一次重投：重打计时起点，退避那段时间因此不计入动作耗时（#134）
                .onRedelivery(this::restartStepTiming)
                .process(ex -> recordFailure(ex))
                .handled(handled);
    }

    /**
     * 一次投递失败（含最后一次）：计数 + **结算本次尝试的动作耗时**（#134）。
     *
     * <p>结算放在异常回调里而不是落库那一刻，是因为落库发生在重试耗尽之后、退避等待之后——
     * 在那里现算的话，一个「连接超时 30 秒、退避 1 秒、重试 2 次」的失败步会写成一个更大的数，
     * 而那正是 ADR-0016 决策 7 拒绝的**步间隔**口径。取最后一次尝试，退避不进去。</p>
     */
    private void noteAttemptFailure(Exchange exchange) {
        countAttempt(exchange);
        settleStepDuration(exchange);
    }

    /** 下一次重投开始前的计时起点（#134）：起点打在退避之后，退避等待因此不算动作耗时。 */
    private void restartStepTiming(Exchange exchange) {
        exchange.setProperty(STEP_STARTED_AT, System.currentTimeMillis());
    }

    /**
     * 标记「这一步的动作从这里开始」（#134）：起点打在动作执行之前，落库时与之相减。
     *
     * <p>顺带清掉上一步结算好的那个值——属性是挂在 exchange 上的，不清就会串到下一步头上。</p>
     */
    private void beginStep(Exchange exchange) {
        exchange.removeProperty(STEP_DURATION_MS);
        exchange.setProperty(STEP_STARTED_AT, System.currentTimeMillis());
    }

    /** 结算本步动作耗时（#134）：把「起点到现在」落成显式值、清掉起点；没有起点（未计时）时什么都不做。 */
    private void settleStepDuration(Exchange exchange) {
        Long started = exchange.getProperty(STEP_STARTED_AT, Long.class);
        if (started == null) {
            return;
        }
        exchange.setProperty(STEP_DURATION_MS, System.currentTimeMillis() - started);
        exchange.removeProperty(STEP_STARTED_AT);
    }

    /**
     * 本步动作耗时（#134），落库时调。
     *
     * <p>**开着的起点优先**：重投过又成功的那一步，显式值是上一次失败尝试的时长，而起点是本次成功尝试
     * 的（{@link #restartStepTiming}），要的是后者。起点不在（已结算 / 压根没计时）才退回显式值；
     * 两个都没有时给 {@code null}——「没测」不编成 {@code 0}。</p>
     */
    private Long stepDuration(Exchange exchange) {
        Long started = exchange.getProperty(STEP_STARTED_AT, Long.class);
        if (started != null) {
            return System.currentTimeMillis() - started;
        }
        return exchange.getProperty(STEP_DURATION_MS, Long.class);
    }

    /**
     * 记一次失败尝试（#109）：异常回调在每次投递失败时都会走一次，末次（重试耗尽）也在其中。
     * 计数按**步骤**归零——同一条消息里前面步骤的重投次数不能算到当前步骤头上
     * （入口链 `handled=true` 失败后链还会往下走，后一个步骤成功时极易串味）。
     */
    private void countAttempt(Exchange exchange) {
        Integer current = actionIndex(exchange);
        int occurred = failedAttempts(exchange, current);
        exchange.setProperty(FAILED_ATTEMPTS_STEP, current);
        exchange.setProperty(FAILED_ATTEMPTS, occurred + 1);
    }

    /** 这个步骤（动作序号）已失败几次；计数属于别的步骤（或还没计过）时为 0。 */
    private int failedAttempts(Exchange exchange, Integer step) {
        Integer counted = exchange.getProperty(FAILED_ATTEMPTS_STEP, Integer.class);
        if (step == null || !step.equals(counted)) {
            return 0;
        }
        Integer occurred = exchange.getProperty(FAILED_ATTEMPTS, Integer.class);
        return occurred == null ? 0 : occurred;
    }

    private void beginRun(Exchange ex, Job job) {
        Long runId = messageHistory.startRun(job.getId(), ex.getMessage().getBody()).getId();
        ex.setProperty(MessageHistoryService.RUN_ID, runId);
    }

    private void beginMessage(Exchange ex, String businessKeyConfig) {
        // 元素 → 已初始化消息：消息标识、业务键、父消息标识、批内序号都归那个 module 管（#139）。
        // 读库这条路上父消息与批内序号都是空的（一行一条消息、读取批没有「第几条」的语义）。
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
     * <p>读库那档「每读一批一个新号」落在 {@link #readBatch} 上：一批里的每条源行共用同一个号，
     * 于是「哪几条是同一批」在库里答得出来。</p>
     */
    private BatchStamp batchStamp(Exchange ex) {
        return new BatchStamp(
                ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class),
                ex.getProperty(MessageHistoryService.BATCH_SEQ, Integer.class),
                ex.getProperty(MessageHistoryService.PARENT_MESSAGE_ID, String.class));
    }

    private void recordSuccessStep(Exchange ex, Action action, Object input) {
        recordSuccessStep(ex, action, input, ex.getMessage().getBody());
    }

    /**
     * 记一条成功消息步。{@code data} 单独传（而不是一律取当前 body）：HTTP 步收尾时 body 已被还原成请求体，
     * 而 {@code data_snapshot} 要记的是**响应**（见 {@link #captureResponse}）。
     *
     * <p>耗时（#134）从 {@link #stepDuration} 取——起点是本步动作执行之前打的那一个，
     * 重投过的步取的是**这次成功那次尝试**（见该方法里那条优先级说明）。记完就把起点收掉，
     * 理由见方法体里那段。</p>
     */
    private void recordSuccessStep(Exchange ex, Action action, Object input, Object data) {
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
        ex.removeProperty(STEP_STARTED_AT);
        messageHistory.recordSuccess(runId, messageId, action.getActionIndex(), action.getName(),
                input, data,
                ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class), attemptsOnSuccess(ex),
                durationMs, batchStamp(ex));
    }

    private void recordFailure(Exchange ex) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        if (runId != null) {
            messageHistory.finishRun(runId, "FAILED", rootMessage(ex));
        }
        String messageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
        if (messageId == null) {
            // READ_SQL 源在 split 前失败：未产生任何源行，用批量占位消息 id 记一条 FAILED 步
            messageId = ex.getProperty(MessageHistoryService.BATCH_MSG_ID, String.class);
        }
        Integer index = ex.getProperty(MessageHistoryService.ACTION_INDEX, Integer.class);
        boolean onlyMembers = Boolean.TRUE.equals(ex.getProperty(BATCH_NO_SUMMARY, Boolean.class));
        if (messageId != null && index != null && !onlyMembers) {
            messageHistory.recordFailure(runId, messageId, index,
                    ex.getProperty(MessageHistoryService.ACTION_NAME, String.class),
                    rootMessage(ex),
                    ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class),
                    ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class),
                    attemptsOnFailure(ex),
                    // #134：失败步也记耗时——而且这里拿到的是**最后一次尝试**的动作耗时
                    // （异常回调里已经结算过，退避等待不在里面）
                    stepDuration(ex),
                    // #139：失败步也带批次戳——只记成功那条路的话，「哪几条是同一批」在失败那一格上就断了
                    batchStamp(ex));
        }
        // 合并那一批（#142）：请求失败时，这一批**每条消息各记一行** FAILED（同因，各自带自己那一片），
        // 摘要行由上一条路记（那时 MESSAGE_ID 已被置成 batch:<批号>）。两条路写同一行时是 upsert，不会重
        List<AggregateBatch.Carrier> carriers = ex.getProperty(BATCH_MANIFEST, List.class);
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

    private void finishRun(Exchange ex, String status, String error) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        clearPendingBatches(runId);
        if (runId != null) {
            messageHistory.finishRun(runId, status, error);
        }
    }

    private boolean isResumeSkipped(Exchange ex, int index) {
        Integer resumeFrom = ex.getProperty(MessageHistoryService.RESUME_FROM, Integer.class);
        return resumeFrom != null && index < resumeFrom;
    }

    /**
     * 失败步的**总尝试次数**（#109）= 该步失败次数（这个步骤每次投递都失败，最后一次也进了异常回调）。
     * 原实现读 exchange property {@code CamelRedeliveryCounter}，但 Camel 4 把它写进 message header，
     * 该表达式恒为 {@code null + 1}——历史页「尝试次数」因此永远显示 1。
     */
    private int attemptsOnFailure(Exchange exchange) {
        int failed = failedAttempts(exchange, actionIndex(exchange));
        return Math.max(failed, 1);
    }

    /** 成功步的总尝试次数（#109）= 该步之前失败几次 + 这次成功（成功不经过异常回调）。 */
    private int attemptsOnSuccess(Exchange exchange) {
        return failedAttempts(exchange, actionIndex(exchange)) + 1;
    }

    private Integer actionIndex(Exchange exchange) {
        return exchange.getProperty(MessageHistoryService.ACTION_INDEX, Integer.class);
    }

    private String rootMessage(Exchange exchange) {
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

    @SuppressWarnings("unchecked")
    private Map<Integer, Object> context(Exchange exchange) {
        Object raw = exchange.getProperty(CONTEXT, Object.class);
        if (raw instanceof Map<?, ?> map) {
            return (Map<Integer, Object>) map;
        }
        Map<Integer, Object> context = new LinkedHashMap<>();
        context.put(0, exchange.getMessage().getBody());
        exchange.setProperty(CONTEXT, context);
        return context;
    }

    /**
     * 读水位（进入循环前）：从缓存库取上次位置，作为本批次的下界（epoch 毫秒 / 自增值）。
     *
     * <p>这里也先打一个批号：读库在**读第一批之前**就失败时（水位库连不上、SQL 语法错），一条源行都没
     * 产生，失败步要用它当占位消息标识（{@link #recordFailure}）。真正的批号由 {@link #readBatch}
     * 每批现给一个——这个只作兜底，不指望它分组（ADR-0017 决策 3）。</p>
     */
    private void prepareRead(Exchange exchange, Job job, ReadSqlConfig read, int actionIndex, String actionName) {
        exchange.setProperty(MessageHistoryService.ACTION_INDEX, actionIndex);
        exchange.setProperty(MessageHistoryService.ACTION_NAME, actionName);
        exchange.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
        long value = watermarkStore.get("job:" + job.getId());
        exchange.setProperty(WATERMARK, value);
        // 让首批至少执行一次（do-while 语义下无副作用；while 语义下需要先入循环）
        exchange.setProperty(HAS_MORE, true);
    }

    /**
     * 读一批：keyset 分页（谓词 + ORDER BY 列 + LIMIT batchSize），计算批内水位并标记是否还有下一批。
     *
     * <p>**读库每读一批一个新批号**（#139）：批号打在进入本批 split 之前，于是这一批的每条源行
     * 共用同一个号（split 会把 exchange 属性拷给子 exchange）。一批一个号而不是一次运行一个号，
     * 是「哪几条是同一批」在库里答得出来的前提（ADR-0017 决策 2）。</p>
     */
    private void readBatch(Exchange exchange, Job job, ReadSqlConfig read) {
        exchange.setProperty(MessageHistoryService.BATCH_MSG_ID, ElementMessageFactory.newBatchId());
        // #134：起点与结算都打在**这一批**上（`prepareRead` 在循环外只跑一次，起点放那儿的话
        // 第二批起就量的是第一批那个数）。读库失败时起点已经在，异常回调照样结算得出来。
        beginStep(exchange);
        long current = exchange.getProperty(WATERMARK, Long.class);
        javax.sql.DataSource ds = customerDataSource(read.dataSourceId());
        Object bound = readSqlBatcher.parseType(read) == WatermarkType.TIMESTAMP
                ? readSqlBatcher.formatTimeBound(current, ScheduleConfig.from(job).zone())
                : current;
        String sql = readSqlBatcher.batchSql(read, requireDialect(read.dataSourceId()));
        // SQL 里没有 `{watermark}`（调 batchSql 时没法把谓词塞进去，比如源表没有可用的水位列、
        // 或者查询本身是聚合的）时就没有参数可绑：照旧传 bound 会报「Parameter index out of range」。
        // 有占位符才有参数，这一格跟着 SQL 走，不跟着配置走。
        List<Map<String, Object>> rows = sql.contains("?")
                ? new JdbcTemplate(ds).queryForList(sql, bound)
                : new JdbcTemplate(ds).queryForList(sql);
        long newWatermark = readSqlBatcher.newBatchWatermark(read, rows, current, ScheduleConfig.from(job).zone());
        exchange.getMessage().setBody(rows);
        exchange.setProperty(WATERMARK, newWatermark);
        exchange.setProperty(HAS_MORE, readSqlBatcher.hasMore(read, rows));
        // 读完了就结算（#134）：与不分批那条路同一口径——落库在每条源行上，在 split 之前结算，
        // 各源行共用**这一次读**的耗时
        settleStepDuration(exchange);
    }

    /**
     * 整批处理成功后才推进水位（持久化）；批失败时不执行，实现"批失败只丢该批"。
     *
     * <p>不需要动作配置：批内水位早由 {@link #readBatch} 算好写进 {@code edge.readWatermark}（#79 曾带过
     * 一个 {@code ReadSqlConfig} 参数，但从加上那天起函数体就没引用过——清理时去掉）。</p>
     */
    private void updateWatermark(Exchange exchange, Job job) {
        Long value = exchange.getProperty(WATERMARK, Long.class);
        if (value != null) {
            watermarkStore.put("job:" + job.getId(), value);
        }
    }

    private boolean moreBatches(Exchange exchange) {
        return Boolean.TRUE.equals(exchange.getProperty(HAS_MORE, Boolean.class));
    }

    private void upsert(Exchange exchange, JdbcTemplate jdbc, String table, String idempotencyKey,
                        SqlDialect dialect) {
        Map<String, Object> row = exchange.getMessage().getBody(Map.class);
        jdbc.update(buildUpsertSql(row, table, idempotencyKey, dialect), row.values().toArray());
    }

    /**
     * 生成幂等 upsert SQL（#67）：动态列 + 业务键，写法归方言（MySQL 的 {@code on duplicate key update}、
     * SQL Server 的 {@code MERGE}，ADR-0014）。包内可见以便单测校验幂等语义（同业务键重复执行不产生重复行）。
     */
    String buildUpsertSql(Map<String, Object> row, String table, String idempotencyKey, SqlDialect dialect) {
        return dialect.upsert(table, new ArrayList<>(row.keySet()), idempotencyKey);
    }

    /** MySQL 方言的 upsert SQL：同包历史单测的入口；生产路径一律显式传方言（见上一个重载）。 */
    String buildUpsertSql(Map<String, Object> row, String table, String idempotencyKey) {
        return buildUpsertSql(row, table, idempotencyKey, SqlDialects.of(DataSourceType.MYSQL));
    }

    private String composeUrl(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        if (base.endsWith("/")) {
            return base + path.replaceFirst("^/", "");
        }
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * 取动作选定的客户库连接池并确保其已登记为 {@code edge-ds-<id>} bean（懒建），
     * 供 READ_SQL 的 {@code #edge-ds-<id>} 与 WRITE_UPSERT 的 {@link JdbcTemplate} 使用。
     * 数据源缺失或不是关系库时注册期即失败，避免把读写错误落到错误库。
     */
    private javax.sql.DataSource customerDataSource(Long dataSourceId) {
        javax.sql.DataSource pool = dataSourceManager.get(dataSourceId);
        if (pool == null) {
            throw new IllegalArgumentException("动作引用的数据源不可用（不是关系库或未配置）: " + dataSourceId);
        }
        return pool;
    }

    /** 取动作选定数据源的方言（与连接池同一份判断，池建好时记下）；拿不到时当场报错，不猜一个默认方言。 */
    private SqlDialect requireDialect(Long dataSourceId) {
        SqlDialect dialect = dataSourceManager.dialect(dataSourceId);
        if (dialect == null) {
            throw new IllegalArgumentException("动作引用的数据源不可用（不是关系库或未配置）: " + dataSourceId);
        }
        return dialect;
    }

}
