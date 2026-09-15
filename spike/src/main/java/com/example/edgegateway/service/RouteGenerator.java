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
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleConfig;
import com.example.edgegateway.model.WatermarkType;
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
        ReadSqlConfig batchRead = null;
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
                            .loopDoWhile(ex -> moreBatches(ex));
                    current = loop.process(ex -> readBatch(ex, job, read))
                            .split(ExpressionBuilder.bodyExpression());
                    inBatchLoop = true;
                    batchRead = read;
                } else {
                    // 无水位列：全量单次读（不分批、不推进水位），保留原语义
                    String sql = read.sql();
                    current = current
                            .process(ex -> {
                                ex.setProperty(MessageHistoryService.ACTION_INDEX, action.getActionIndex());
                                ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                                ex.setProperty(MessageHistoryService.BATCH_MSG_ID, UUID.randomUUID().toString());
                            })
                            .to("sql:" + sql + "?dataSource=#edge-ds-" + read.dataSourceId() + "&outputType=SelectList")
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
        if (inBatchLoop && batchRead != null) {
            // 先结束 split（把一批的行处理完），再提交该批水位；批失败时不会走到这一步，从而实现"批失败只丢该批"
            ReadSqlConfig read = batchRead;
            current = ((SplitDefinition) current).end();
            current = current.process(ex -> updateWatermark(ex, job, read));
            current = ((LoopDefinition) current).end();
            inSplit = false;
        } else if (inSplit) {
            current = ((SplitDefinition) current).end();
            if (batchRead != null) {
                ReadSqlConfig read = batchRead;
                current = current.process(ex -> updateWatermark(ex, job, read));
            }
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
                yield current.process(ex -> {
                    if (isResumeSkipped(ex, index)) {
                        return;
                    }
                    ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                    ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                    Object input = ex.getMessage().getBody();
                    ex.setProperty(MessageHistoryService.STEP_INPUT, input);
                    upsert(ex, writeJdbc, write.tableName(), write.idempotencyKey());
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
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, index);
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            ex.setProperty(MessageHistoryService.STEP_INPUT, ex.getMessage().getBody());
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
                                .process(ex -> recordSuccessStep(ex, action,
                                        ex.getProperty(MessageHistoryService.STEP_INPUT, Object.class)))
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
                .onExceptionOccurred(this::countAttempt)
                .process(ex -> recordFailure(ex))
                .handled(handled);
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
        Long runId = ex.getProperty(MessageHistoryService.RUN_ID, Long.class);
        String messageId = ex.getProperty(MessageHistoryService.MESSAGE_ID, String.class);
        if (runId == null || messageId == null) {
            return; // 未创建消息（如 MQ_CONSUME 入口反序列化失败）时不记消息步
        }
        messageHistory.recordSuccess(runId, messageId, action.getActionIndex(), action.getName(),
                input, ex.getMessage().getBody(),
                ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class), attemptsOnSuccess(ex));
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
                    attemptsOnFailure(ex));
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
        long current = exchange.getProperty(WATERMARK, Long.class);
        javax.sql.DataSource ds = customerDataSource(read.dataSourceId());
        Object bound = readSqlBatcher.parseType(read) == WatermarkType.TIMESTAMP
                ? readSqlBatcher.formatTimeBound(current, ScheduleConfig.from(job).zone())
                : current;
        String sql = readSqlBatcher.batchSql(read);
        List<Map<String, Object>> rows = new JdbcTemplate(ds).queryForList(sql, bound);
        long newWatermark = readSqlBatcher.newBatchWatermark(read, rows, current, ScheduleConfig.from(job).zone());
        exchange.getMessage().setBody(rows);
        exchange.setProperty(WATERMARK, newWatermark);
        exchange.setProperty(HAS_MORE, readSqlBatcher.hasMore(read, rows));
    }

    /** 整批处理成功后才推进水位（持久化）；批失败时不执行，实现"批失败只丢该批"。 */
    private void updateWatermark(Exchange exchange, Job job, ReadSqlConfig read) {
        Long value = exchange.getProperty(WATERMARK, Long.class);
        if (value != null) {
            watermarkStore.put("job:" + job.getId(), value);
        }
    }

    private boolean moreBatches(Exchange exchange) {
        return Boolean.TRUE.equals(exchange.getProperty(HAS_MORE, Boolean.class));
    }

    private void upsert(Exchange exchange, JdbcTemplate jdbc, String table, String idempotencyKey) {
        Map<String, Object> row = exchange.getMessage().getBody(Map.class);
        jdbc.update(buildUpsertSql(row, table, idempotencyKey), row.values().toArray());
    }

    /**
     * 生成幂等 upsert SQL（#67）：动态列 + 业务键 {@code ON DUPLICATE KEY UPDATE}。
     * 包内可见以便单测校验幂等语义（同业务键重复执行不产生重复行）。
     */
    String buildUpsertSql(Map<String, Object> row, String table, String idempotencyKey) {
        List<String> columns = new ArrayList<>(row.keySet());
        String columnList = String.join(",", columns);
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String updates = columns.stream()
                .filter(c -> !c.equals(idempotencyKey))
                .map(c -> c + " = values(" + c + ")")
                .collect(Collectors.joining(", "));
        return "insert into " + table + " (" + columnList + ") values (" + placeholders + ")"
                + " on duplicate key update "
                + (updates.isEmpty() ? idempotencyKey + " = values(" + idempotencyKey + ")" : updates);
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
     * 数据源缺失或非 MySQL 时注册期即失败，避免把读写错误落到错误库。
     */
    private javax.sql.DataSource customerDataSource(Long dataSourceId) {
        javax.sql.DataSource pool = dataSourceManager.get(dataSourceId);
        if (pool == null) {
            throw new IllegalArgumentException("动作引用的数据源不可用（非 MySQL 或未配置）: " + dataSourceId);
        }
        return pool;
    }

}
