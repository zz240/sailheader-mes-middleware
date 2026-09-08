package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.LoopDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 片段组合生成器：按动作种类组合，现有北向/南向链路行为不变 + MQ_PUBLISH 新组合。 */
class RouteGeneratorTest {

    private DataSourceMapper dataSourceMapper;
    private DataSourceManager dataSourceManager;
    private MessageHistoryService messageHistory;
    private RouteGenerator generator;

    @BeforeEach
    void setUp() {
        dataSourceMapper = mock(DataSourceMapper.class);
        dataSourceManager = mock(DataSourceManager.class);
        messageHistory = mock(MessageHistoryService.class);
        generator = new RouteGenerator(
                dataSourceMapper, mock(WatermarkStore.class), messageHistory, dataSourceManager, new ReadSqlBatcher());
    }

    private DataSource mysqlDataSource(Long id) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddress("jdbc:mysql://localhost:3306/edge_gateway");
        ds.setAddition(Map.of("username", "gateway", "password", "gateway"));
        return ds;
    }

    private DataSource httpDataSource(Long id) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setType(DataSourceType.HTTP);
        ds.setAddress("http://localhost:8080");
        ds.setAddition(Map.of("token", "mock-token"));
        return ds;
    }

    private DataSource rabbitDataSource(Long id) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setType(DataSourceType.RABBITMQ);
        ds.setName("mes.exchange");
        ds.setAddition(Map.of("host", "localhost", "port", 5672));
        return ds;
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }

    private DefaultCamelContext buildContext(Job job, List<Action> actions) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        return context;
    }

    private List<String> collectUris(RouteDefinition route) {
        List<String> uris = new ArrayList<>();
        collectOutputs(route.getOutputs(), uris);
        return uris;
    }

    private void collectOutputs(List<?> outputs, List<String> uris) {
        for (Object output : outputs) {
            if (output instanceof ToDefinition to) {
                uris.add(to.getUri());
            } else if (output instanceof ProcessorDefinition<?> def && def.getOutputs() != null) {
                collectOutputs(def.getOutputs(), uris);
            }
        }
    }

    @Test
    void generatesNorthboundChainWithReadMapHttp() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDataSource(2L));
        when(dataSourceManager.get(1L)).thenReturn(mock(javax.sql.DataSource.class));

        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no, status from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "order_no", "target", "sheet_no")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/mock", "method", "POST")));

        DefaultCamelContext context = buildContext(job, actions);
        assertThat(context.getRouteDefinitions()).hasSize(2);

        RouteDefinition route = findRoute(context, "job-7");
        assertThat(route.getInput().getUri()).isEqualTo("direct:job-7");
        List<String> uris = collectUris(route);
        // READ_SQL 带水位列 → 生成 keyset 分批循环（不再一次全量读进内存）
        assertThat(hasLoop(route)).isTrue();
        verify(dataSourceManager).get(1L);
        assertThat(uris).anyMatch(u -> u.equals("http://localhost:8080/mock"));

        // 自动触发已外置到 JobScheduler；默认（不自动）不再生成 timer 路由
        assertThat(findRoute(context, "timer-job-7")).isNull();
    }

    @Test
    void generatesTimestampWatermarkFromReadSqlAction() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDataSource(2L));
        when(dataSourceManager.get(1L)).thenReturn(mock(javax.sql.DataSource.class));

        Job job = new Job();
        job.setId(8L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id from po_order where {watermark}",
                        "watermarkType", "TIMESTAMP", "watermarkColumn", "updated_at")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "id", "target", "id")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L)));

        DefaultCamelContext context = buildContext(job, actions);
        // TIMESTAMP 同样进入分批循环；谓词语义由 ReadSqlBatcher 单测覆盖
        assertThat(hasLoop(findRoute(context, "job-8"))).isTrue();
        verify(dataSourceManager).get(1L);
    }

    @Test
    void readSqlRoutesToActionSelectedCustomerDataSource() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceManager.get(1L)).thenReturn(mock(javax.sql.DataSource.class));

        Job job = new Job();
        job.setId(11L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "id", "target", "id")))));

        DefaultCamelContext context = buildContext(job, actions);
        // 数据源引用应指向动作选定的客户库池（构造 JdbcTemplate），而非网关自身配置库
        verify(dataSourceManager).get(1L);
        assertThat(hasLoop(findRoute(context, "job-11"))).isTrue();
    }

    @Test
    void generatesSouthboundChainWithConsumeMapUpsert() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(rabbitDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(mysqlDataSource(2L));
        when(dataSourceManager.get(2L)).thenReturn(mock(javax.sql.DataSource.class));

        Job job = new Job();
        job.setId(9L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))),
                action(3, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));

        DefaultCamelContext context = buildContext(job, actions);
        assertThat(context.getRouteDefinitions()).hasSize(3);

        RouteDefinition route = context.getRouteDefinitions().get(0);
        // #82 兼容断言：不配 exchange/exchangeType/routingKey 时 URI 与现状一致（数据源名 + direct + routingKey=queue）
        assertThat(route.getInput().getUri()).isEqualTo(
                "spring-rabbitmq:mes.exchange?exchangeType=direct&queues=mes.to.edge&routingKey=mes.to.edge&autoDeclare=true");

        RouteDefinition replay = findRoute(context, "direct-9");
        assertThat(replay).isNotNull();
        assertThat(replay.getInput().getUri()).isEqualTo("direct:job-9");
        // WRITE_UPSERT 必须用动作选定客户库的连接池构造 JdbcTemplate（#67），而非全局配置库；
        // 消费主路由 + 重放路由 + 断点续跑路由各构建一次动作链
        verify(dataSourceManager, times(3)).get(2L);
    }

    /** #67 幂等 upsert SQL：动态列 + 业务键 ON DUPLICATE KEY UPDATE，同业务键重复执行不产生重复行。 */
    @Test
    void writeUpsertBuildsIdempotentSqlByBusinessKey() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_no", "PO-1");
        row.put("qty", 2);
        row.put("product", "M-1");

        String sql = generator.buildUpsertSql(row, "po_receipt", "order_no");
        assertThat(sql).startsWith("insert into po_receipt (");
        assertThat(sql).contains("on duplicate key update");
        assertThat(sql).contains("qty = values(qty)");
        assertThat(sql).contains("product = values(product)");
        // 业务键不作 update 目标，避免覆盖主键语义
        assertThat(sql).doesNotContain("order_no = values(order_no)");
    }

    /** WRITE_UPSERT 引用的客户库不可用（非 MySQL/未配置）时应在注册期快速失败，避免写入静默落到配置库（#67 AC1）。 */
    @Test
    void writeUpsertWithUnavailableCustomerDataSourceFailsAtRouteBuild() {
        when(dataSourceManager.get(2L)).thenReturn(null);

        Job job = new Job();
        job.setId(12L);
        List<Action> actions = List.of(
                action(1, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));

        // 触发 addRoutes/configure 才会逐动作构建，进而走 customerWriteJdbc 的注册期校验
        assertThatThrownBy(() -> buildContext(job, actions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据源不可用");
    }

    /** 重放入口：创建运行与消息上下文，body 原样透传（手动执行/断点重放共用）；重投走 resume 路由，不再依赖 edge.originalInput。 */
    @Test
    void replayEntryCreatesRunAndMessageContext() throws Exception {
        when(dataSourceMapper.selectById(18L)).thenReturn(rabbitDataSource(18L));

        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 18L, "queue", "bom.to.edge")));

        DefaultCamelContext context = buildContext(job, actions);
        when(messageHistory.startRun(anyLong(), any())).thenReturn(new JobRun());
        // 摘掉 MQ 消费路由，只启动重放路由，避免测试依赖真实 RabbitMQ
        context.removeRouteDefinition(context.getRouteDefinition("job-7"));
        context.start();
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("bomId", "BOM-1");
            input.put("items", List.of(Map.of("lineNo", 1, "itemCode", "M-1")));
            Exchange out = context.createProducerTemplate()
                    .request("direct:job-7", ex -> ex.getMessage().setBody(input));

            assertThat(out.getMessage().getBody()).isEqualTo(input);
            assertThat(out.getProperty(MessageHistoryService.MESSAGE_ID, Object.class)).isNotNull();
            verify(messageHistory).startRun(anyLong(), any());
        } finally {
            context.stop();
        }
    }

    @Test
    void generatesChainWithHttpWriteAndMqPublish() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDataSource(2L));
        when(dataSourceMapper.selectById(3L)).thenReturn(rabbitDataSource(3L));
        when(dataSourceManager.get(1L)).thenReturn(mock(javax.sql.DataSource.class));

        Job job = new Job();
        job.setId(10L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select * from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "order_no", "target", "sheet_no")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/mock", "method", "POST")),
                action(4, ActionKind.MQ_PUBLISH, Map.of("dataSourceId", 3L, "exchange", "edge.notify",
                        "routingKey", "notify.q", "bodySourceStep", 2)));

        DefaultCamelContext context = buildContext(job, actions);
        List<String> uris = collectUris(findRoute(context, "job-10"));
        assertThat(uris).anyMatch(u -> u.equals("http://localhost:8080/mock"));
        assertThat(uris).anyMatch(u -> u.contains("spring-rabbitmq") && u.contains("edge.notify"));
    }

    /** #82 消费入口使用动作侧 exchange 名/类型/binding key（ADR-0010）：自定义 exchange + topic 模式串原样进 URI。 */
    @Test
    void consumerUsesActionExchangeNameTypeAndBindingKey() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(rabbitDataSource(1L));

        Job job = new Job();
        job.setId(20L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge",
                        "exchange", "edge.topic.x", "exchangeType", "topic", "routingKey", "order.#")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))));

        DefaultCamelContext context = buildContext(job, actions);
        RouteDefinition route = findRoute(context, "job-20");
        assertThat(route.getInput().getUri()).isEqualTo(
                "spring-rabbitmq:edge.topic.x?exchangeType=topic&queues=mes.to.edge&routingKey=order.#&autoDeclare=true");
    }

    private RouteDefinition findRoute(DefaultCamelContext context, String routeId) {
        return context.getRouteDefinitions().stream()
                .filter(r -> routeId.equals(r.getRouteId()))
                .findFirst()
                .orElse(null);
    }

    private boolean hasLoop(RouteDefinition route) {
        return hasLoopOutput(route.getOutputs());
    }

    private boolean hasLoopOutput(List<?> outputs) {
        for (Object output : outputs) {
            if (output instanceof LoopDefinition) {
                return true;
            }
            if (output instanceof ProcessorDefinition<?> def && def.getOutputs() != null && hasLoopOutput(def.getOutputs())) {
                return true;
            }
        }
        return false;
    }
}
