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
import com.example.edgegateway.model.WatermarkType;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.ExpressionBuilder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * 轮询入口（direct + 定时）。READ_SQL 会 split(body())，其后的片段在行内执行。
 * 错误处理统一：onException（重试/退避）+ 消息步 FAILED 记录 + 运行 FAILED 标记。
 * 每个任务额外生成"断点续跑路由"（direct:job-&lt;id&gt;-resume），供失败消息步精确重投。</p>
 */
@Component
public class RouteGenerator {

    private static final String CONTEXT = "edge.context";
    private static final String ORIGINAL_INPUT = "edge.originalInput";
    private static final String SKIP_FLAG = "edge.skipFragment";

    private final DataSourceMapper dataSourceMapper;
    private final WatermarkStore watermarkStore;
    private final MessageHistoryService messageHistory;
    private final DataSourceManager dataSourceManager;

    public RouteGenerator(
            DataSourceMapper dataSourceMapper,
            WatermarkStore watermarkStore,
            MessageHistoryService messageHistory,
            DataSourceManager dataSourceManager) {
        this.dataSourceMapper = dataSourceMapper;
        this.watermarkStore = watermarkStore;
        this.messageHistory = messageHistory;
        this.dataSourceManager = dataSourceManager;
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

    /** 轮询入口：direct + 定时，按片段组合。 */
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
                from("timer:" + routeId + "?period=60000&delay=5000")
                        .routeId("timer-" + routeId)
                        .to("direct:" + routeId);
            }
        };
    }

    /** 消费入口：MQ 消费主路由（MQ_CONSUME 为入口）。 */
    private RouteBuilder buildConsumerEntry(Job job, List<Action> actions) {
        String routeId = "job-" + job.getId();
        MqConsumeConfig consume = (MqConsumeConfig) ActionConfig.from(
                ActionKind.MQ_CONSUME, actions.getFirst().getAddition());
        DataSource mqDs = dataSourceMapper.selectById(consume.dataSourceId());
        return new RouteBuilder() {
            @Override
            public void configure() {
                configureErrorHandling(this, job, true);
                ProcessorDefinition<?> route = from("spring-rabbitmq:" + mqDs.getName()
                                + "?queues=" + consume.queue()
                                + "&routingKey="
                                + (consume.routingKey() == null ? consume.queue() : consume.routingKey())
                                + "&autoDeclare=true")
                        .routeId(routeId)
                        .unmarshal().json()
                        .process(ex -> {
                            Map<Integer, Object> context = context(ex);
                            context.put(actions.getFirst().getActionIndex(), ex.getMessage().getBody());
                            ex.setProperty(ORIGINAL_INPUT, ex.getMessage().getBody());
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
                            ex.setProperty(ORIGINAL_INPUT, ex.getMessage().getBody());
                            ex.setProperty(CONTEXT, context);
                            beginRun(ex, job);
                            beginMessage(ex, consume.businessKey());
                        });
                ProcessorDefinition<?> chain = appendChain(route, actions.subList(1, actions.size()), job);
                chain.process(ex -> finishRun(ex, "SUCCESS", null));
            }
        };
    }

    /** 断点续跑路由：direct 入口接收失败步数据，从 {@code edge.resumeFromStep} 动作起重放。 */
    private RouteBuilder buildResumeRoute(Job job, List<Action> actions) {
        String routeId = "resume-" + job.getId();
        return new RouteBuilder() {
            @Override
            public void configure() {
                configureErrorHandling(this, job, false);
                RouteDefinition route = from("direct:job-" + job.getId() + "-resume")
                        .routeId(routeId)
                        .process(ex -> {
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
        ReadSqlConfig watermarkRead = null;
        for (Action action : actions) {
            ActionKind kind = action.getType();
            ActionConfig config = ActionConfig.from(kind, action.getAddition());
            if (kind == ActionKind.READ_SQL && !inSplit) {
                ReadSqlConfig read = (ReadSqlConfig) config;
                // 确保动作选定的客户库连接池已建立并绑定 edge-ds-<id> bean，路由启动才能按名解析
                customerDataSource(read.dataSourceId());
                String sql = read.sql().replace("{watermark}", watermarkPredicate(read));
                current = current
                        .process(ex -> {
                            ex.setProperty(MessageHistoryService.ACTION_INDEX, action.getActionIndex());
                            ex.setProperty(MessageHistoryService.ACTION_NAME, action.getName());
                            // READ 失败（未产生任何源行）时用于落一条 FAILED 消息步的占位消息 id
                            ex.setProperty(MessageHistoryService.BATCH_MSG_ID, UUID.randomUUID().toString());
                            readWatermark(ex, job, read);
                        })
                        .to("sql:" + sql + "?dataSource=#edge-ds-" + read.dataSourceId() + "&outputType=SelectList")
                        .process(ex -> computeMax(ex, job, read))
                        .split(ExpressionBuilder.bodyExpression());
                inSplit = true;
                watermarkRead = read;
                // 源行为"消息创建"时机：每条源行 = 一条消息，在 split 内首部创建消息标识与业务键
                current = current.process(ex -> beginMessage(ex, read.businessKey()));
                // 读取动作本身也作为链上一步：每条源行记一条 SUCCESS 消息步（无输入快照，data=该行）
                current = current.process(ex -> recordSuccessStep(ex, action, null));
            }
            current = appendFragment(current, action, config, job);
        }
        if (inSplit) {
            current = ((org.apache.camel.model.SplitDefinition) current).end();
            if (watermarkRead != null && watermarkRead.watermarkColumn() != null) {
                ReadSqlConfig read = watermarkRead;
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
                            // bodySourceStep 缺省取上下文中最近一次输出（同步+通知场景 = FIELD_MAP 结果）
                            Map<Integer, Object> context = context(ex);
                            Integer step = publish.bodySourceStep();
                            if (step == null) {
                                step = context.keySet().stream()
                                        .filter(k -> k > 0)
                                        .max(Integer::compareTo)
                                        .orElse(0);
                            }
                            Object source = context.get(step);
                            if (source != null) {
                                ex.getMessage().setBody(source);
                            }
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
                .process(ex -> recordFailure(ex))
                .handled(handled);
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
                ex.getProperty(MessageHistoryService.BUSINESS_KEY, String.class), attempt(ex));
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
                    attempt(ex));
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

    private int attempt(Exchange exchange) {
        Integer counter = exchange.getProperty(Exchange.REDELIVERY_COUNTER, Integer.class);
        return (counter == null ? 0 : counter) + 1;
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

    private String watermarkPredicate(ReadSqlConfig read) {
        return WatermarkType.TIMESTAMP == parseWatermark(read)
                ? read.watermarkColumn() + " > :#lastRun"
                : read.watermarkColumn() + " > :#lastId";
    }

    private WatermarkType parseWatermark(ReadSqlConfig read) {
        return read.watermarkType() == null ? WatermarkType.SEQUENCE : WatermarkType.valueOf(read.watermarkType());
    }

    private void readWatermark(Exchange exchange, Job job, ReadSqlConfig read) {
        long value = watermarkStore.get("job:" + job.getId());
        if (parseWatermark(read) == WatermarkType.TIMESTAMP) {
            exchange.getMessage().setHeader("lastRun", formatTime(value));
        } else {
            exchange.getMessage().setHeader("lastId", value);
        }
    }

    private void computeMax(Exchange exchange, Job job, ReadSqlConfig read) {
        List<Map<String, Object>> rows = exchange.getMessage().getBody(List.class);
        long value;
        if (parseWatermark(read) == WatermarkType.SEQUENCE) {
            long max = exchange.getMessage().getHeader("lastId", Long.class);
            for (Map<String, Object> row : rows) {
                Object raw = row.get(read.watermarkColumn());
                if (raw instanceof Number number) {
                    max = Math.max(max, number.longValue());
                }
            }
            value = max;
        } else {
            value = System.currentTimeMillis();
        }
        exchange.setProperty("watermarkValue", value);
    }

    private void updateWatermark(Exchange exchange, Job job, ReadSqlConfig read) {
        Long value = exchange.getProperty("watermarkValue", Long.class);
        if (value != null) {
            watermarkStore.put("job:" + job.getId(), value);
        }
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

    private String formatTime(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }
}
