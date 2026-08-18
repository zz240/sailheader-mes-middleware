package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.HttpDataSourceConfig;
import com.example.edgegateway.config.RabbitMqDataSourceConfig;
import com.example.edgegateway.config.RemoteAccessConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapping.FieldMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionType;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.WatermarkType;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 路由生成器：把"任务 + 动作链"配置编译成一条 Camel 路由。
 * 目前支持两种演示模板：
 * <ul>
 *   <li>北向：MySQL 读（带水位）→ 字段映射 → HTTP 写</li>
 *   <li>南向：RabbitMQ 消费 → 字段映射 → MySQL 幂等 upsert</li>
 * </ul>
 * SQL 中的 {@code {watermark}} 占位符会被替换成任务配置的水位谓词。
 */
@Component
public class RouteGenerator {

    private final DataSourceMapper dataSourceMapper;
    private final WatermarkStore watermarkStore;
    private final JdbcTemplate jdbcTemplate;
    private final DeadLetterService deadLetterService;

    public RouteGenerator(
            DataSourceMapper dataSourceMapper,
            WatermarkStore watermarkStore,
            JdbcTemplate jdbcTemplate,
            DeadLetterService deadLetterService) {
        this.dataSourceMapper = dataSourceMapper;
        this.watermarkStore = watermarkStore;
        this.jdbcTemplate = jdbcTemplate;
        this.deadLetterService = deadLetterService;
    }

    public List<RouteBuilder> build(Job job, List<Action> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("任务没有动作，无法生成路由");
        }
        List<Action> sorted = actions.stream()
                .sorted(Comparator.comparing(Action::getActionIndex))
                .toList();
        if (isRabbitConsumer(sorted.get(0))) {
            // 南向：MQ 消费主路由 + direct 重放路由（供死信重投/manual 执行）
            return List.of(buildSouthbound(job, sorted), buildSouthboundReplay(job, sorted));
        }
        return List.of(buildNorthbound(job, sorted));
    }

    /** 南向重放路由：direct 入口接收已解析的输入（死信重投/手动执行），跑映射 + upsert。 */
    private RouteBuilder buildSouthboundReplay(Job job, List<Action> actions) {
        String routeId = "direct-" + job.getId();
        RemoteAccessConfig write = RemoteAccessConfig.from(addition(remoteActions(actions).get(remoteActions(actions).size() - 1)));
        Map<String, Object> writeAddition = addition(remoteActions(actions).get(remoteActions(actions).size() - 1));
        String table = str(writeAddition.get("tableName"));
        String idempotencyKey = str(writeAddition.get("idempotencyKey"));
        FieldMappingConfig mapping = fieldMapping(actions);
        FieldMapper fieldMapper = new FieldMapper(List.of());
        int writeIndex = remoteActions(actions).get(remoteActions(actions).size() - 1).getActionIndex();
        int mappingIndex = actions.stream()
                .filter(a -> a.getType() == ActionType.FIELD_MAPPING)
                .findFirst()
                .map(Action::getActionIndex)
                .orElse(-1);
        int retries = job.getRetryCount() == null ? 0 : job.getRetryCount();
        long backoff = job.getBackoffMs() == null ? 1000L : job.getBackoffMs();

        return new RouteBuilder() {
            @Override
            public void configure() {
                onException(Exception.class)
                        .maximumRedeliveries(retries)
                        .redeliveryDelay(backoff)
                        .process(exchange -> deadLetterService.record(
                                job.getId(),
                                exchange.getProperty("edge.actionIndex", Integer.class),
                                exchange.getProperty("edge.originalInput",
                                        exchange.getMessage().getBody()),
                                exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)))
                        .handled(false); // 重放失败必须上抛，让调用方（重投）感知
                from("direct:" + job.getId())
                        .routeId(routeId)
                        .process(exchange -> {
                            exchange.setProperty("edge.originalInput", exchange.getMessage().getBody());
                            exchange.setProperty("edge.actionIndex", mappingIndex);
                            Map<String, Object> source = exchange.getMessage().getBody(Map.class);
                            exchange.getMessage().setBody(fieldMapper.apply(source, mapping.rules()));
                        })
                        .process(exchange -> {
                            exchange.setProperty("edge.actionIndex", writeIndex);
                            upsert(exchange, table, idempotencyKey);
                        })
                        .log(routeId + " replayed ${body}");
            }
        };
    }

    private boolean isRabbitConsumer(Action action) {
        RemoteAccessConfig config = RemoteAccessConfig.from(addition(action));
        if (config.dataSourceId() == null) {
            return false;
        }
        DataSource dataSource = dataSourceMapper.selectById(config.dataSourceId());
        return dataSource != null && dataSource.getType() == DataSourceType.RABBITMQ;
    }

    private RouteBuilder buildNorthbound(Job job, List<Action> actions) {
        String routeId = "job-" + job.getId();
        RemoteAccessConfig read = RemoteAccessConfig.from(addition(remoteActions(actions).get(0)));
        RemoteAccessConfig write = RemoteAccessConfig.from(addition(remoteActions(actions).get(remoteActions(actions).size() - 1)));
        DataSource writeDataSource = dataSourceMapper.selectById(write.dataSourceId());
        HttpDataSourceConfig http = (HttpDataSourceConfig)
                DataSourceConfig.from(writeDataSource.getType(), writeDataSource.getAddition());
        FieldMappingConfig mapping = fieldMapping(actions);
        FieldMapper fieldMapper = new FieldMapper(List.of());
        int readIndex = remoteActions(actions).get(0).getActionIndex();
        int writeIndex = remoteActions(actions).get(remoteActions(actions).size() - 1).getActionIndex();
        int mappingIndex = actions.stream()
                .filter(a -> a.getType() == ActionType.FIELD_MAPPING)
                .findFirst()
                .map(Action::getActionIndex)
                .orElse(-1);
        int retries = job.getRetryCount() == null ? 0 : job.getRetryCount();
        long backoff = job.getBackoffMs() == null ? 1000L : job.getBackoffMs();

        String sql = read.sql().replace("{watermark}", watermarkPredicate(job));
        String authHeader = http.token() == null || http.token().isBlank()
                ? null
                : "Bearer " + http.token();

        return new RouteBuilder() {
            @Override
            public void configure() {
                onException(Exception.class)
                        .maximumRedeliveries(retries)
                        .redeliveryDelay(backoff)
                        .process(exchange -> deadLetterService.record(
                                job.getId(),
                                exchange.getProperty("edge.actionIndex", Integer.class),
                                exchange.getProperty("edge.originalInput",
                                        exchange.getMessage().getBody()),
                                exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)))
                        .handled(false); // 失败即停：异常继续上抛，执行状态 FAILED，水位不推进
                // 主链路：direct 入口供手动执行（可携带任务输入）
                from("direct:" + routeId)
                        .routeId(routeId)
                        .process(exchange -> {
                            exchange.setProperty("edge.actionIndex", readIndex);
                            readWatermark(exchange, job);
                        })
                        .to("sql:" + sql + "?dataSource=#dataSource&outputType=SelectList")
                        .process(exchange -> computeMax(exchange, job))
                        .split(body())
                        .process(exchange -> {
                            exchange.setProperty("edge.actionIndex", mappingIndex);
                            Map<String, Object> source = exchange.getMessage().getBody(Map.class);
                            exchange.setProperty("edge.originalInput", source);
                            exchange.getMessage().setBody(fieldMapper.apply(source, mapping.rules()));
                        })
                        .marshal().json()
                        .setHeader(Exchange.HTTP_METHOD, constant(http.method()))
                        .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                        .process(exchange -> {
                            exchange.setProperty("edge.actionIndex", writeIndex);
                            if (authHeader != null) {
                                exchange.getMessage().setHeader("Authorization", authHeader);
                            }
                        })
                        .to(writeDataSource.getAddress())
                        .end()
                        .process(exchange -> updateWatermark(exchange, job));
                // 定时入口：timer 触发后转发到 direct
                from("timer:" + routeId + "?period=60000&delay=5000")
                        .routeId("timer-" + routeId)
                        .to("direct:" + routeId);
            }
        };
    }

    private RouteBuilder buildSouthbound(Job job, List<Action> actions) {
        String routeId = "job-" + job.getId();
        RemoteAccessConfig consumer = RemoteAccessConfig.from(addition(actions.get(0)));
        DataSource mqDataSource = dataSourceMapper.selectById(consumer.dataSourceId());
        RabbitMqDataSourceConfig mq = (RabbitMqDataSourceConfig)
                DataSourceConfig.from(mqDataSource.getType(), mqDataSource.getAddition());

        RemoteAccessConfig write = RemoteAccessConfig.from(addition(remoteActions(actions).get(remoteActions(actions).size() - 1)));
        Map<String, Object> writeAddition = addition(remoteActions(actions).get(remoteActions(actions).size() - 1));
        String table = str(writeAddition.get("tableName"));
        String idempotencyKey = str(writeAddition.get("idempotencyKey"));
        FieldMappingConfig mapping = fieldMapping(actions);
        FieldMapper fieldMapper = new FieldMapper(List.of());
        int consumerIndex = actions.get(0).getActionIndex();
        int writeIndex = remoteActions(actions).get(remoteActions(actions).size() - 1).getActionIndex();
        int mappingIndex = actions.stream()
                .filter(a -> a.getType() == ActionType.FIELD_MAPPING)
                .findFirst()
                .map(Action::getActionIndex)
                .orElse(-1);
        int retries = job.getRetryCount() == null ? 0 : job.getRetryCount();
        long backoff = job.getBackoffMs() == null ? 1000L : job.getBackoffMs();

        return new RouteBuilder() {
            @Override
            public void configure() {
                onException(Exception.class)
                        .maximumRedeliveries(retries)
                        .redeliveryDelay(backoff)
                        .process(exchange -> deadLetterService.record(
                                job.getId(),
                                exchange.getProperty("edge.actionIndex", Integer.class),
                                exchange.getProperty("edge.originalInput",
                                        exchange.getMessage().getBody()),
                                exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)))
                        .handled(true); // MQ 消费：记录死信后视为已处理，避免 broker 无限重投
                from("spring-rabbitmq:" + mqDataSource.getName()
                        + "?queues=" + mq.queue() + "&routingKey=" + mq.queue() + "&autoDeclare=true")
                        .routeId(routeId)
                        .unmarshal().json()
                        .process(exchange -> {
                            exchange.setProperty("edge.actionIndex", mappingIndex);
                            Map<String, Object> source = exchange.getMessage().getBody(Map.class);
                            exchange.setProperty("edge.originalInput", source);
                            exchange.getMessage().setBody(fieldMapper.apply(source, mapping.rules()));
                        })
                        .process(exchange -> {
                            exchange.setProperty("edge.actionIndex", writeIndex);
                            upsert(exchange, table, idempotencyKey);
                        })
                        .log(routeId + " upserted ${body}");
            }
        };
    }

    private List<Action> remoteActions(List<Action> actions) {
        return actions.stream()
                .filter(a -> a.getType() == ActionType.REMOTE_ACCESS)
                .toList();
    }

    private FieldMappingConfig fieldMapping(List<Action> actions) {
        return actions.stream()
                .filter(a -> a.getType() == ActionType.FIELD_MAPPING)
                .findFirst()
                .map(a -> FieldMappingConfig.from(addition(a)))
                .orElse(null);
    }

    private Map<String, Object> addition(Action action) {
        return action.getAddition() == null ? Map.of() : action.getAddition();
    }

    private String watermarkPredicate(Job job) {
        String column = job.getWatermarkColumn();
        return job.getWatermarkType() == WatermarkType.TIMESTAMP
                ? column + " > :#lastRun"
                : column + " > :#lastId";
    }

    private String watermarkKey(Job job) {
        return "job:" + job.getId();
    }

    private void readWatermark(Exchange exchange, Job job) {
        long value = watermarkStore.get(watermarkKey(job));
        if (job.getWatermarkType() == WatermarkType.TIMESTAMP) {
            exchange.getMessage().setHeader("lastRun", formatTime(value));
        } else {
            exchange.getMessage().setHeader("lastId", value);
        }
    }

    private void computeMax(Exchange exchange, Job job) {
        List<Map<String, Object>> rows = exchange.getMessage().getBody(List.class);
        long value;
        if (job.getWatermarkType() == WatermarkType.SEQUENCE) {
            long max = exchange.getMessage().getHeader("lastId", Long.class);
            String column = job.getWatermarkColumn();
            for (Map<String, Object> row : rows) {
                Object raw = row.get(column);
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

    private void updateWatermark(Exchange exchange, Job job) {
        Long value = exchange.getProperty("watermarkValue", Long.class);
        if (value != null) {
            watermarkStore.put(watermarkKey(job), value);
        }
    }

    private void upsert(Exchange exchange, String table, String idempotencyKey) {
        Map<String, Object> row = exchange.getMessage().getBody(Map.class);
        List<String> columns = new ArrayList<>(row.keySet());
        String columnList = String.join(",", columns);
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String updates = columns.stream()
                .filter(c -> !c.equals(idempotencyKey))
                .map(c -> c + " = values(" + c + ")")
                .collect(Collectors.joining(", "));
        String sql = "insert into " + table + " (" + columnList + ") values (" + placeholders + ")"
                + " on duplicate key update "
                + (updates.isEmpty() ? idempotencyKey + " = values(" + idempotencyKey + ")" : updates);
        jdbcTemplate.update(sql, columns.stream().map(row::get).toArray());
    }

    private String formatTime(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }
}
