package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionType;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.WatermarkType;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ProcessDefinition;
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
        ds.setAddition(Map.of());
        return ds;
    }

    private DataSource rabbitDataSource(Long id) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setType(DataSourceType.RABBITMQ);
        ds.setName("mes.exchange");
        ds.setAddition(Map.of("queue", "mes.to.edge", "host", "localhost", "port", 5672));
        return ds;
    }

    private Action action(int index, ActionType type, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setType(type);
        action.setAddition(addition);
        return action;
    }

    @Test
    void generatesNorthboundRouteWithSqlMappingAndHttp() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDataSource(2L));

        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        job.setWatermarkType(WatermarkType.SEQUENCE);
        job.setWatermarkColumn("id");
        List<Action> actions = List.of(
                action(1, ActionType.REMOTE_ACCESS, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no, status from po_order where {watermark} order by id")),
                action(2, ActionType.FIELD_MAPPING, Map.of("rules",
                        List.of(Map.of("source", "order_no", "target", "sheet_no")))),
                action(3, ActionType.REMOTE_ACCESS, Map.of("dataSourceId", 2L, "path", "/mock", "method", "POST")));

        DefaultCamelContext context = new DefaultCamelContext();
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        assertThat(context.getRouteDefinitions()).hasSize(2);

        RouteDefinition route = findRoute(context, "job-7");
        assertThat(route.getInput().getUri()).isEqualTo("direct:job-7");

        long[] count = new long[1];
        List<String> toUris = new ArrayList<>();
        collect(route, toUris, count);

        assertThat(toUris).anyMatch(u -> u.contains("sql:") && u.contains("id > :#lastId"));
        assertThat(toUris).anyMatch(u -> u.startsWith("http://localhost:8080/mock"));
        assertThat(count[0]).isGreaterThanOrEqualTo(2);

        RouteDefinition timer = findRoute(context, "timer-job-7");
        assertThat(timer.getInput().getUri()).startsWith("timer:job-7");
        List<String> timerUris = new ArrayList<>();
        collect(timer, timerUris, new long[1]);
        assertThat(timerUris).anyMatch(u -> u.equals("direct:job-7"));
    }

    @Test
    void generatesTimestampWatermarkPredicate() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDataSource(2L));

        Job job = new Job();
        job.setId(8L);
        job.setWatermarkType(WatermarkType.TIMESTAMP);
        job.setWatermarkColumn("updated_at");
        List<Action> actions = List.of(
                action(1, ActionType.REMOTE_ACCESS, Map.of("dataSourceId", 1L,
                        "sql", "select id from po_order where {watermark}")),
                action(2, ActionType.FIELD_MAPPING, Map.of("rules",
                        List.of(Map.of("source", "id", "target", "id")))),
                action(3, ActionType.REMOTE_ACCESS, Map.of("dataSourceId", 2L, "path", "/mock", "method", "POST")));

        DefaultCamelContext context = new DefaultCamelContext();
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }

        RouteDefinition route = findRoute(context, "job-8");
        List<String> toUris = new ArrayList<>();
        collect(route, toUris, new long[1]);
        assertThat(toUris).anyMatch(u -> u.contains("updated_at > :#lastRun"));
    }

    @Test
    void generatesSouthboundRouteFromRabbitMq() throws Exception {
        when(dataSourceMapper.selectById(1L)).thenReturn(rabbitDataSource(1L));
        when(dataSourceMapper.selectById(2L)).thenReturn(mysqlDataSource(2L));

        Job job = new Job();
        job.setId(9L);
        job.setStatus(1);
        List<Action> actions = List.of(
                action(1, ActionType.REMOTE_ACCESS, Map.of("dataSourceId", 1L)),
                action(2, ActionType.FIELD_MAPPING, Map.of("rules",
                        List.of(Map.of("source", "orderNo", "target", "order_no")))),
                action(3, ActionType.REMOTE_ACCESS, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));

        DefaultCamelContext context = new DefaultCamelContext();
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }

        RouteDefinition route = context.getRouteDefinitions().get(0);
        assertThat(route.getInput().getUri()).contains("spring-rabbitmq").contains("mes.to.edge");
        // 南向应有 MQ 主路由 + direct 重放路由（供死信重投）
        assertThat(context.getRouteDefinitions()).hasSize(2);
        RouteDefinition replay = findRoute(context, "direct-9");
        assertThat(replay).isNotNull();
        assertThat(replay.getInput().getUri()).isEqualTo("direct:job-9");
        long[] count = new long[1];
        collect(route, new ArrayList<>(), count);
        long processCount = count[0];
        assertThat(processCount).isGreaterThanOrEqualTo(2);
    }

    /** 递归收集路由模型里的 to 端点 URI，并统计 process 数量（split 内部也会收集）。 */
    private void collect(RouteDefinition route, List<String> toUris, long[] processCount) {
        collectOutputs(route.getOutputs(), toUris, processCount);
    }

    @SuppressWarnings("unchecked")
    private void collectOutputs(List<?> outputs, List<String> toUris, long[] processCount) {
        for (Object output : outputs) {
            if (output instanceof ToDefinition to) {
                toUris.add(to.getUri());
            } else if (output instanceof ProcessDefinition) {
                processCount[0]++;
            }
            if (output instanceof org.apache.camel.model.ProcessorDefinition<?> definition) {
                collectOutputs(definition.getOutputs(), toUris, processCount);
            }
        }
    }

    private RouteDefinition findRoute(DefaultCamelContext context, String routeId) {
        return context.getRouteDefinitions().stream()
                .filter(r -> routeId.equals(r.getRouteId()))
                .findFirst()
                .orElseThrow();
    }
}
