package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 片段组合生成器：按动作种类组合，现有北向/南向链路行为不变 + MQ_PUBLISH 新组合。 */
class RouteGeneratorTest {

    private DataSourceMapper dataSourceMapper;
    private RouteGenerator generator;

    @BeforeEach
    void setUp() {
        dataSourceMapper = mock(DataSourceMapper.class);
        generator = new RouteGenerator(
                dataSourceMapper, mock(WatermarkStore.class), mock(JdbcTemplate.class), mock(DeadLetterService.class));
    }

    private DataSource mysqlDataSource(Long id) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition(Map.of("url", "jdbc:mysql://localhost:3306/edge_gateway",
                "username", "gateway", "password", "gateway"));
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

        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no, status from po_order where {watermark} order by id",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "order_no", "target", "sheet_no")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/mock", "method", "POST")));

        DefaultCamelContext context = buildContext(job, actions);
        assertThat(context.getRouteDefinitions()).hasSize(2);

        RouteDefinition route = findRoute(context, "job-7");
        assertThat(route.getInput().getUri()).isEqualTo("direct:job-7");
        List<String> uris = collectUris(route);
        assertThat(uris).anyMatch(u -> u.contains("sql:") && u.contains("id > :#lastId"));
        assertThat(uris).anyMatch(u -> u.equals("http://localhost:8080/mock"));

        RouteDefinition timer = findRoute(context, "timer-job-7");
        assertThat(timer.getInput().getUri()).startsWith("timer:job-7");
    }

    @Test
    void generatesTimestampWatermarkFromReadSqlAction() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDataSource(2L));

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
        List<String> uris = collectUris(findRoute(context, "job-8"));
        assertThat(uris).anyMatch(u -> u.contains("updated_at > :#lastRun"));
    }

    @Test
    void generatesSouthboundChainWithConsumeMapUpsert() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(rabbitDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(mysqlDataSource(2L));

        Job job = new Job();
        job.setId(9L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))),
                action(3, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));

        DefaultCamelContext context = buildContext(job, actions);
        assertThat(context.getRouteDefinitions()).hasSize(2);

        RouteDefinition route = context.getRouteDefinitions().get(0);
        assertThat(route.getInput().getUri()).contains("spring-rabbitmq").contains("mes.to.edge");

        RouteDefinition replay = findRoute(context, "direct-9");
        assertThat(replay).isNotNull();
        assertThat(replay.getInput().getUri()).isEqualTo("direct:job-9");
    }

    @Test
    void generatesChainWithHttpWriteAndMqPublish() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDataSource(2L));
        when(dataSourceMapper.selectById(3L)).thenReturn(rabbitDataSource(3L));

        Job job = new Job();
        job.setId(10L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select * from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "order_no", "target", "sheet_no")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/mock", "method", "POST")),
                action(4, ActionKind.MQ_PUBLISH, Map.of("dataSourceId", 3L, "queue", "notify.q")));

        DefaultCamelContext context = buildContext(job, actions);
        List<String> uris = collectUris(findRoute(context, "job-10"));
        assertThat(uris).anyMatch(u -> u.equals("http://localhost:8080/mock"));
        assertThat(uris).anyMatch(u -> u.contains("spring-rabbitmq") && u.contains("routingKey=notify.q"));
    }

    private RouteDefinition findRoute(DefaultCamelContext context, String routeId) {
        return context.getRouteDefinitions().stream()
                .filter(r -> routeId.equals(r.getRouteId()))
                .findFirst()
                .orElse(null);
    }
}
