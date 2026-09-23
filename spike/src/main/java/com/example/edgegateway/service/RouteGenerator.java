package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.config.ActionConfig;
import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.HttpCallConfig;
import com.example.edgegateway.config.HttpDataSourceConfig;
import com.example.edgegateway.config.MqConsumeConfig;
import com.example.edgegateway.config.MqPublishConfig;
import com.example.edgegateway.config.ReadSqlConfig;
import com.example.edgegateway.config.WriteUpsertConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapping.JsonataFieldMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleConfig;
import com.example.edgegateway.model.WatermarkType;
import com.example.edgegateway.service.sql.SqlDialect;
import com.example.edgegateway.service.sql.SqlDialects;
import org.apache.camel.Exchange;
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

    private final DataSourceMapper dataSourceMapper;
    private final WatermarkStore watermarkStore;
    private final MessageHistoryService messageHistory;
    private final DataSourceManager dataSourceManager;
    private final ReadSqlBatcher readSqlBatcher;

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
        return builders;
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
        // 捕获内容不做截断：装不下就当场失败（可读错误），绝不写半截 JSON（#123 口径 4；列容量本身是 #127）
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
                                ex.setProperty(MessageHistoryService.BATCH_MSG_ID, UUID.randomUUID().toString());
                                beginStep(ex); // #134：计时起点打在读库之前
                            })
                            .to("sql:" + sql + "?dataSource=#edge-ds-" + read.dataSourceId() + "&outputType=SelectList")
                            // 读完了就结算（#134）：落库发生在每条源行上，而读只做了一次。
                            // 在 split 之前结算，各源行共用这一个数；不然第二条源行量到的是「读库 + 前一条跑完」
                            .process(this::settleStepDuration)
                            .split(ExpressionBuilder.bodyExpression());
                }
                inSplit = true;
                // 源行为"消息创建"时机：每条源行 = 一条消息，在 split 内首部创建消息标识与业务键
                current = current.process(ex -> beginMessage(ex, read.businessKey()));
                // 读取动作本身也作为链上一步：每条源行记一条 SUCCESS 消息步（无输入快照，data=该行）
                current = current.process(ex -> recordSuccessStep(ex, action, null));
            }
            current = appendFragment(current, action, config, job);
        }
        if (inBatchLoop) {
            // 先结束 split（把一批的行处理完），再提交该批水位；批失败时不会走到这一步，从而实现"批失败只丢该批"
            current = ((SplitDefinition) current).end();
            current = current.process(ex -> updateWatermark(ex, job));
            current = ((LoopDefinition) current).end();
        } else if (inSplit) {
            current = ((SplitDefinition) current).end();
        }
        return current;
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
        ex.setProperty(MessageHistoryService.MESSAGE_ID, UUID.randomUUID().toString());
        String existing = ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class);
        String key = existing != null
                ? existing
                : MessageHistoryService.extractBusinessKey(ex.getMessage().getBody(), businessKeyConfig);
        ex.setProperty(MessageHistoryService.BUSINESS_KEY, key);
        ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
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
                durationMs);
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
        if (messageId != null && index != null) {
            messageHistory.recordFailure(runId, messageId, index,
                    ex.getProperty(MessageHistoryService.ACTION_NAME, String.class),
                    rootMessage(ex),
                    ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class),
                    ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class),
                    attemptsOnFailure(ex),
                    // #134：失败步也记耗时——而且这里拿到的是**最后一次尝试**的动作耗时
                    // （异常回调里已经结算过，退避等待不在里面）
                    stepDuration(ex));
        }
    }

    private void finishRun(Exchange ex, String status, String error) {
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
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

    /** 读水位（进入循环前）：从缓存库取上次位置，作为本批次的下界（epoch 毫秒 / 自增值）。 */
    private void prepareRead(Exchange exchange, Job job, ReadSqlConfig read, int actionIndex, String actionName) {
        exchange.setProperty(MessageHistoryService.ACTION_INDEX, actionIndex);
        exchange.setProperty(MessageHistoryService.ACTION_NAME, actionName);
        exchange.setProperty(MessageHistoryService.BATCH_MSG_ID, UUID.randomUUID().toString());
        long value = watermarkStore.get("job:" + job.getId());
        exchange.setProperty(WATERMARK, value);
        // 让首批至少执行一次（do-while 语义下无副作用；while 语义下需要先入循环）
        exchange.setProperty(HAS_MORE, true);
    }

    /** 读一批：keyset 分页（谓词 + ORDER BY 列 + LIMIT batchSize），计算批内水位并标记是否还有下一批。 */
    private void readBatch(Exchange exchange, Job job, ReadSqlConfig read) {
        // #134：起点与结算都打在**这一批**上（`prepareRead` 在循环外只跑一次，起点放那儿的话
        // 第二批起就量的是第一批那个数）。读库失败时起点已经在，异常回调照样结算得出来。
        beginStep(exchange);
        long current = exchange.getProperty(WATERMARK, Long.class);
        javax.sql.DataSource ds = customerDataSource(read.dataSourceId());
        Object bound = readSqlBatcher.parseType(read) == WatermarkType.TIMESTAMP
                ? readSqlBatcher.formatTimeBound(current, ScheduleConfig.from(job).zone())
                : current;
        String sql = readSqlBatcher.batchSql(read, requireDialect(read.dataSourceId()));
        List<Map<String, Object>> rows = new JdbcTemplate(ds).queryForList(sql, bound);
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
