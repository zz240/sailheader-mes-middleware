package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.service.sql.SqlDialects;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息历史最高 seam：跑一条真实 Camel 链（MQ_CONSUME → FIELD_MAP），
 * 断言产生了 job_run 与 message_step 行；随后从失败消息步断点续跑成功。
 */
class MessageHistoryExecutionTest {

    @BeforeAll
    static void initEntityTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageStep.class);
        TableInfoHelper.initTableInfo(assistant, JobRun.class);
        TableInfoHelper.initTableInfo(assistant, Action.class);
        // Job 也要初始化：任务启用校验 + isSourceAction 都是 LambdaQueryWrapper（#108 之后失败会真的抛出来）
        TableInfoHelper.initTableInfo(assistant, Job.class);
    }

    private final DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
    private final WatermarkStore watermarkStore = mock(WatermarkStore.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);

    private final Map<Long, JobRun> runs = new ConcurrentHashMap<>();
    private final Map<Long, MessageStep> steps = new ConcurrentHashMap<>();

    private long runSeq = 1;
    private long stepSeq = 1;

    /** #123 用例的 HTTP 替身：现场可改的状态码/报文，以及"目标端被调了几次"。 */
    private final java.util.concurrent.atomic.AtomicInteger httpCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger httpStatus =
            new java.util.concurrent.atomic.AtomicInteger(200);
    private final java.util.concurrent.atomic.AtomicReference<String> httpBody =
            new java.util.concurrent.atomic.AtomicReference<>("{}");

    private MessageHistoryService service(DefaultCamelContext context) {
        MessageHistoryService s = new MessageHistoryService(
                jobRunMapper, messageStepMapper, jobMapper, actionMapper, new ObjectMapper(), context,
                new JobLastRunWriter(jobMapper));
        return s;
    }

    @Test
    void executionRecordsJobRunAndMessageStepRows() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(7L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "bom.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))));
        stubConsumeDataSource();

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        // 摘掉 MQ 消费路由，只走重放路由
        context.removeRouteDefinition(context.getRouteDefinition("job-7"));
        context.start();
        try {
            Map<String, Object> input = Map.of("orderNo", "PO-1", "qty", 3);
            context.createProducerTemplate().requestBody("direct:job-7", input);
        } finally {
            context.stop();
        }

        assertThat(runs.values()).anyMatch(r -> r.getJobId().equals(7L) && "SUCCESS".equals(r.getStatus()));
        ArgumentCaptor<MessageStep> captor = ArgumentCaptor.forClass(MessageStep.class);
        verify(messageStepMapper, atLeastOnce()).insert(captor.capture());
        MessageStep step = captor.getValue();
        assertThat(step.getStatus()).isEqualTo("SUCCESS");
        assertThat(step.getStep()).isEqualTo(2);
        assertThat(step.getActionName()).isEqualTo("map");
        assertThat(step.getDurationMs())
                .as("#134：跑完一条成功链，落库的这一步要带动作耗时（不是空的）")
                .isNotNull();
    }

    @Test
    void resumeFromFailedStepMarksRedeliveredAndCreatesRun() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(9L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))));
        stubConsumeDataSource();

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        context.removeRouteDefinition(context.getRouteDefinition("job-9"));
        context.start();
        try {
            JobRun failedRun = new JobRun();
            failedRun.setId(runSeq++);
            failedRun.setJobId(9L);
            failedRun.setStatus("FAILED");
            runs.put(failedRun.getId(), failedRun);
            MessageStep failed = new MessageStep();
            failed.setId(stepSeq++);
            failed.setRunId(failedRun.getId());
            failed.setMessageId("msg-f");
            failed.setStep(2);
            failed.setActionName("map");
            failed.setStatus("FAILED");
            failed.setInputSnapshot("{\"orderNo\":\"PO-9\"}");
            steps.put(failed.getId(), failed);

            Job enabled = new Job();
            enabled.setId(9L);
            enabled.setStatus(1);
            when(jobMapper.selectById(9L)).thenReturn(enabled);

            MessageStep result = history.redeliver(failed.getId());

            assertThat(result.getStatus()).isEqualTo("REDELIVERED");
            assertThat(steps.get(failed.getId()).getStatus()).isEqualTo("REDELIVERED");
            assertThat(runs.values()).anyMatch(r -> r.getJobId().equals(9L) && "SUCCESS".equals(r.getStatus()));
        } finally {
            context.stop();
        }
    }

    /**
     * #90：链 READ_SQL(1) → FIELD_MAP(2) → HTTP_CALL(3, 首次返回 500 → 步骤 3 失败) → FIELD_MAP(4, sourceStep=2)。
     * 重投从步骤 3 起跑：被跳过的步骤 2 的 data_snapshot 必须沿 {@code edge.resumeContext} 回填 context，
     * 否则步骤 4 的 {@code $s_2} 未定义、映射结果为 null，落库快照会变成 null（而非 {"sheet_no_copied":"PO-1"}）。
     * 真实 Camel 链 + 真实 H2 客户库 + 本地 mock 目标（先 500 后 200，模拟"修好依赖再重投"）。
     */
    @Test
    void resumeRestoresUpstreamSnapshotsSoMappingMatchesFirstRun() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        Job job = job(18L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no, vendor_code from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "businessKey", "order_no")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "order_no", "target", "sheet_no")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/receive", "method", "POST")),
                action(4, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 2, "source", "sheet_no", "target", "sheet_no_copied")))));
        bindInMemory();

        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:resumeOk;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (java.sql.Connection c = h2.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("create table po_order(id bigint primary key, order_no varchar(64), vendor_code varchar(64))");
            st.execute("insert into po_order(id, order_no, vendor_code) values (1,'PO-1','V1')");
        }
        when(dataSourceManager.get(1L)).thenReturn(h2);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        context.getRegistry().bind("edge-ds-1", h2);

        // 目标先不可用（500）→ 首次运行在步骤 3 失败；重投前"修复"为 200
        java.util.concurrent.atomic.AtomicBoolean targetUp = new java.util.concurrent.atomic.AtomicBoolean(false);
        HttpServer mes = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mes.createContext("/receive", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            boolean up = targetUp.get();
            exchange.sendResponseHeaders(up ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mes.start();

        DataSource httpDs = new DataSource();
        httpDs.setId(2L);
        httpDs.setType(DataSourceType.HTTP);
        httpDs.setAddress("http://127.0.0.1:" + mes.getAddress().getPort());
        httpDs.setAddition(Map.of());
        when(dataSourceMapper.selectById(2L)).thenReturn(httpDs);

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        context.start();
        when(jobMapper.selectById(18L)).thenReturn(job(18L));
        try {
            assertThatThrownBy(() ->
                    context.createProducerTemplate().requestBody("direct:job-18", Map.of()))
                    .isInstanceOf(Exception.class);

            // 首次运行：步骤 3 失败（目标 500），失败即停 → 步骤 4 未执行
            MessageStep failed = steps.values().stream()
                    .filter(s -> "FAILED".equals(s.getStatus()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("首次运行未记录 FAILED 消息步 | steps=" + describeSteps()));
            assertThat(failed.getStep()).as("失败步序号 | steps=" + describeSteps()).isEqualTo(3);
            assertThat(steps.values().stream()
                    .filter(s -> s.getRunId().equals(failed.getRunId()))
                    .map(MessageStep::getStep))
                    .as("首次运行只跑到步骤 3 | steps=" + describeSteps())
                    .containsExactlyInAnyOrder(1, 2, 3);

            // 依赖已恢复 → 重投成功，原失败步标 REDELIVERED
            targetUp.set(true);
            MessageStep result = history.redeliver(failed.getId());

            assertThat(result.getStatus()).isEqualTo("REDELIVERED");
            assertThat(steps.get(failed.getId()).getStatus()).isEqualTo("REDELIVERED");
            JobRun redeliveredRun = runs.values().stream()
                    .filter(r -> "SUCCESS".equals(r.getStatus()))
                    .max(java.util.Comparator.comparing(JobRun::getId))
                    .orElseThrow(() -> new AssertionError("重投未产生 SUCCESS 运行 | runs=" + describeRuns(18L)));

            // 重投从步骤 3 起跑：步骤 2 被跳过、步骤 3/4 执行
            List<MessageStep> redeliveredSteps = steps.values().stream()
                    .filter(s -> s.getRunId().equals(redeliveredRun.getId()))
                    .toList();
            assertThat(redeliveredSteps).extracting(MessageStep::getStep)
                    .as("重投执行步骤 | steps=" + describeSteps())
                    .containsExactlyInAnyOrder(3, 4);

            // 关键断言：$s_2 来自被跳过的步骤 2 的快照 → 映射结果非空（未注入时为 null）
            MessageStep redeliveredMapped = redeliveredSteps.stream()
                    .filter(s -> s.getStep() == 4)
                    .findFirst()
                    .orElseThrow();
            assertThat(redeliveredMapped.getDataSnapshot())
                    .as("重投后步骤 4 的映射结果")
                    .contains("\"sheet_no_copied\":\"PO-1\"");
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /**
     * #123 口径 1/2：HTTP_CALL 的响应进 {@code context[本动作序号]}，后续 FIELD_MAP 以 {@code sourceStep}
     * 取响应字段——B6「标识服务注册 → 拿 handle」的形状（{@code results[0].handle}）。
     *
     * <p>同时断言 #126：HTTP_CALL 出站后链上消息体仍是本次请求体。证据是下游 FIELD_MAP 的
     * {@code input_snapshot}——它是该动作开始时的 body；若响应顶替了消息体，那里会是响应。
     */
    @Test
    void httpCallResponseIsAddressableByFollowingFieldMapAndKeepsRequestBody() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(19L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 0, "source", "FCode", "target", "FCode")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")),
                action(4, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 3, "source", "results[0].handle", "target", "FHandle")))));
        stubConsumeDataSource();

        httpBody.set("{\"code\":\"0\",\"results\":[{\"FCode\":\"C-1\",\"handle\":\"88.1.200020\"}]}");
        HttpServer mes = startHttpTarget();
        stubHttpDataSource(2L, mes);
        try {
            buildRoutes(context, history, job, actions);
            context.createProducerTemplate().requestBody("direct:job-19", Map.of("FCode", "C-1"));

            assertThat(httpCalls.get()).isEqualTo(1);
            JobRun run = runOf(19L);
            assertThat(run).as("运行记录 | runs=" + describeRuns(19L)).isNotNull();
            assertThat(run.getStatus()).isEqualTo("SUCCESS");

            List<MessageStep> runSteps = stepsOf(run);
            MessageStep call = stepOf(runSteps, 3);
            assertThat(call.getInputSnapshot()).as("HTTP 步的 input = 请求").contains("\"FCode\":\"C-1\"");
            assertThat(call.getDataSnapshot()).as("HTTP 步的 data = 响应（语义不变）")
                    .contains("\"handle\":\"88.1.200020\"");

            MessageStep mapped = stepOf(runSteps, 4);
            assertThat(mapped.getDataSnapshot()).as("响应字段被后续 FIELD_MAP 取到（sourceStep=HTTP_CALL 序号）")
                    .contains("\"FHandle\":\"88.1.200020\"");
            assertThat(mapped.getInputSnapshot()).as("#126：HTTP_CALL 出站后消息体仍是请求体")
                    .contains("\"FCode\":\"C-1\"")
                    .doesNotContain("handle");
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /**
     * #126 回归：响应为空（204 / 空 200）时紧随其后的 WRITE_UPSERT 不再 NPE。
     * 原行为是 HTTP 响应顶替消息体 → body = null → upsert 对 null 取 keySet 直接 NPE，
     * 一次**成功**的调用被判成整链失败。
     */
    @Test
    void emptyHttpResponseDoesNotBreakFollowingWriteUpsert() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(20L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 0, "source", "FCode", "target", "FCode"),
                        Map.of("sourceStep", 0, "source", "handle", "target", "FHandle")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")),
                action(4, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 3L, "tableName", "mdm_code", "idempotencyKey", "FCode")));
        stubConsumeDataSource();

        httpStatus.set(204);
        httpBody.set(null);
        HttpServer mes = startHttpTarget();
        stubHttpDataSource(2L, mes);

        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:httpEmpty;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        execute(h2, "create table mdm_code(FCode varchar(64) primary key, FHandle varchar(64))");
        when(dataSourceManager.get(3L)).thenReturn(h2);
        when(dataSourceManager.dialect(3L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        try {
            buildRoutes(context, history, job, actions);
            context.createProducerTemplate()
                    .requestBody("direct:job-20", Map.of("FCode", "C-1", "handle", "88.1.200020"));

            JobRun run = runOf(20L);
            assertThat(run).as("运行记录 | runs=" + describeRuns(20L)).isNotNull();
            assertThat(run.getStatus()).as("空响应不该把成功的调用判成失败 | " + describeSteps())
                    .isEqualTo("SUCCESS");
            assertThat(queryString(h2, "select FHandle from mdm_code where FCode = 'C-1'"))
                    .as("写回的是请求数据，不是响应")
                    .isEqualTo("88.1.200020");
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /** #123 口径 3：目标端 5xx 时不写 context、不写回，链路按 ADR-0003 失败即停。 */
    @Test
    void httpCallFailureStopsChainWithoutWritingBack() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(21L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 0, "source", "FCode", "target", "FCode"),
                        Map.of("sourceStep", 0, "source", "handle", "target", "FHandle")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")),
                action(4, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 3L, "tableName", "mdm_code", "idempotencyKey", "FCode")));
        stubConsumeDataSource();

        httpStatus.set(500);
        httpBody.set("boom");
        HttpServer mes = startHttpTarget();
        stubHttpDataSource(2L, mes);

        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:httpFail;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        execute(h2, "create table mdm_code(FCode varchar(64) primary key, FHandle varchar(64))");
        when(dataSourceManager.get(3L)).thenReturn(h2);
        when(dataSourceManager.dialect(3L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        try {
            buildRoutes(context, history, job, actions);
            assertThatThrownBy(() -> context.createProducerTemplate()
                    .requestBody("direct:job-21", Map.of("FCode", "C-1", "handle", "88.1.200020")))
                    .isInstanceOf(Exception.class);

            JobRun failed = runOf(21L);
            assertThat(failed).as("运行记录 | runs=" + describeRuns(21L)).isNotNull();
            assertThat(failed.getStatus()).isEqualTo("FAILED");
            assertThat(stepsOf(failed)).extracting(MessageStep::getStep)
                    .as("失败即停：只跑到 HTTP 步 | steps=" + describeSteps())
                    .containsExactlyInAnyOrder(2, 3);
            // 目标端失败 → 捕获根本没发生：该步没有 data_snapshot（响应不进 context，也没落半截快照）
            assertThat(stepOf(stepsOf(failed), 3).getDataSnapshot())
                    .as("5xx 不写 context / 不落快照").isNull();
            assertThat(queryString(h2, "select FHandle from mdm_code where FCode = 'C-1'"))
                    .as("目标端失败不写回").isNull();
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /**
     * #123 口径 7：HTTP_CALL 步已成功的消息从后续动作续跑时，{@code context[HTTP_CALL 序号]} 由该步的
     * {@code data_snapshot}（响应）经 {@code edge.resumeContext} 重建——步骤 2 因此**不会**被重新调用，
     * 步骤 4 仍能取到响应字段。
     */
    @Test
    void resumeRebuildsHttpCallResponseFromSnapshot() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(22L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 0, "source", "FCode", "target", "FCode")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")),
                action(4, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 3L, "tableName", "mdm_code", "idempotencyKey", "FCode")),
                action(5, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 3, "source", "results[0].handle", "target", "handle_copied")))));
        stubConsumeDataSource();

        httpBody.set("{\"code\":\"0\",\"results\":[{\"handle\":\"88.1.200020\"}]}");
        HttpServer mes = startHttpTarget();
        stubHttpDataSource(2L, mes);

        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:httpResume;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        // 目标表**先不存在**：首次运行在步骤 3 失败（失败即停），修好之后再重投
        when(dataSourceManager.get(3L)).thenReturn(h2);
        when(dataSourceManager.dialect(3L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        try {
            buildRoutes(context, history, job, actions);
            when(jobMapper.selectById(22L)).thenReturn(job(22L));
            assertThatThrownBy(() -> context.createProducerTemplate()
                    .requestBody("direct:job-22", Map.of("FCode", "C-1")))
                    .isInstanceOf(Exception.class);

            MessageStep failed = steps.values().stream()
                    .filter(s -> "FAILED".equals(s.getStatus()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("首次运行未记录 FAILED 消息步 | steps=" + describeSteps()));
            assertThat(failed.getStep()).as("失败步序号 | steps=" + describeSteps()).isEqualTo(4);

            // 修好依赖（建表）后重投：序号 2~4 的动作被跳过，context[3] 只能由 HTTP 步（序号 3）的快照重建
            execute(h2, "create table mdm_code(FCode varchar(64) primary key, FHandle varchar(64))");
            history.redeliver(failed.getId());

            JobRun redelivered = runs.values().stream()
                    .filter(r -> "SUCCESS".equals(r.getStatus()))
                    .max(java.util.Comparator.comparing(JobRun::getId))
                    .orElseThrow(() -> new AssertionError("重投未产生 SUCCESS 运行 | runs=" + describeRuns(22L)));
            List<MessageStep> redeliveredSteps = stepsOf(redelivered);
            assertThat(redeliveredSteps).extracting(MessageStep::getStep)
                    .as("重投执行步骤 | steps=" + describeSteps()).containsExactlyInAnyOrder(4, 5);
            assertThat(stepOf(redeliveredSteps, 5).getDataSnapshot())
                    .as("重投后仍能取到 HTTP 响应字段（context[3] 由 data_snapshot 重建）")
                    .contains("\"handle_copied\":\"88.1.200020\"");
            assertThat(httpCalls.get()).as("HTTP 步（序号 3）被跳过，不该再调一次目标端").isEqualTo(1);
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /**
     * #123 口径 1：顶层是**数组**的响应进 context 是 {@code List}（不是被丢掉、也不是塞成字符串）——
     * 后续规则用 {@code $root[0].FCode} 取得到。
     */
    @Test
    void jsonArrayResponseIsAddressableAsList() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(23L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 0, "source", "FCode", "target", "FCode")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")),
                action(4, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 3, "source", "$root[0].FCode", "target", "FCode")))));
        stubConsumeDataSource();

        httpBody.set("[{\"FCode\":\"C-1\",\"handle\":\"88.1.200020\"}]");
        HttpServer mes = startHttpTarget();
        stubHttpDataSource(2L, mes);
        try {
            buildRoutes(context, history, job, actions);
            context.createProducerTemplate().requestBody("direct:job-23", Map.of("FCode", "C-1"));

            JobRun run = runOf(23L);
            assertThat(run).as("运行记录 | runs=" + describeRuns(23L)).isNotNull();
            assertThat(run.getStatus()).isEqualTo("SUCCESS");
            assertThat(stepOf(stepsOf(run), 4).getDataSnapshot())
                    .as("数组响应的首元素被后续映射取到")
                    .contains("\"FCode\":\"C-1\"");
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /**
     * #123 口径 4：响应大到装不下快照列 → 该动作**显式失败**且错误可读（不是一句 SQL 的 Data too long），
     * 也不落半截快照。界线跟着列宽走（#141 起是 MEDIUMTEXT 的 16 MB），这道闸的行为没变。
     */
    @Test
    void oversizedHttpResponseFailsTheStepWithReadableError() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(24L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 0, "source", "FCode", "target", "FCode")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")));
        stubConsumeDataSource();

        httpBody.set("{\"blob\":\"" + "x".repeat(MessageHistoryService.SNAPSHOT_LIMIT_BYTES + 1) + "\"}");
        HttpServer mes = startHttpTarget();
        stubHttpDataSource(2L, mes);
        try {
            buildRoutes(context, history, job, actions);
            assertThatThrownBy(() -> context.createProducerTemplate()
                    .requestBody("direct:job-24", Map.of("FCode", "C-1")))
                    .isInstanceOf(Exception.class);

            JobRun run = runOf(24L);
            assertThat(run.getStatus()).isEqualTo("FAILED");
            MessageStep failed = stepOf(stepsOf(run), 3);
            assertThat(failed.getStatus()).isEqualTo("FAILED");
            assertThat(failed.getError()).as("错误要说人话：哪一步、多少字节、装不下")
                    .contains("data_snapshot")
                    .contains(String.valueOf(MessageHistoryService.SNAPSHOT_LIMIT_BYTES));
            assertThat(failed.getDataSnapshot()).as("绝不写半截").isNull();
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /**
     * #141：同一条链、同一个「大小由目标端决定」的响应，只是这一次落在抬起来的列容量之内——
     * **动作成功、运行成功、快照是完整 JSON**（不是半截、也没被截断）。
     *
     * <p>与上一条是一对：界线从 64 KB 抬到 16 MB，而 #123 那道闸的**行为**（显式失败、绝不写半截）没变。
     * 列型本身在真 MySQL 上验（`SnapshotCapacityMigrationTest`）——H2 的 TEXT 是 CLOB，
     * 旧界线在 H2 上复现不出来。</p>
     */
    @Test
    void bigCapturePayloadLandsWholeAndTheRunSucceeds() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(25L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 0, "source", "FCode", "target", "FCode")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")));
        stubConsumeDataSource();

        // 300 KB 量级的一条报文（旧列型上这一条会被判 Data too long，动作成功却被记成失败；#141 起列是 MEDIUMTEXT）。
        String report = "{\"lines\":[" + "{\"FSeq\":1},".repeat(30_000) + "{\"FSeq\":30000}]}";
        assertThat(report.length()).as("这条报文比旧列型的上限大、又在新列容量之内")
                .isGreaterThan(65_535)
                .isLessThan(MessageHistoryService.SNAPSHOT_LIMIT_BYTES);
        httpBody.set(report);
        HttpServer mes = startHttpTarget();
        stubHttpDataSource(2L, mes);
        try {
            buildRoutes(context, history, job, actions);
            context.createProducerTemplate().requestBody("direct:job-25", Map.of("FCode", "C-1"));

            JobRun run = runOf(25L);
            assertThat(run.getStatus()).as("这一次是成功，不是「动作成功却被判失败」").isEqualTo("SUCCESS");
            MessageStep captured = stepOf(stepsOf(run), 3);
            assertThat(captured.getStatus()).isEqualTo("SUCCESS");
            assertThat(captured.getDataSnapshot()).as("快照是完整报文：尾巴还在，长度也对得上")
                    .hasSizeGreaterThan(65_535)
                    .endsWith("{\"FSeq\":30000}]}");
        } finally {
            context.stop();
            mes.stop(0);
        }
    }

    /** #71 AC3：跑一条会失败的链 → 表中出现一条 FAILED run + 一条 FAILED 消息步（含动作序号/错误）。 */
    @Test
    void failedChainRecordsOneRunAndOneFailedStep() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(13L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "bom.to.edge")),
                action(2, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));
        stubConsumeDataSource();

        // WRITE_UPSERT 连接会在执行时抛 SQLException → 触发 onException → 记 FAILED 步
        javax.sql.DataSource broken = mock(javax.sql.DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection broken"));
        when(dataSourceManager.get(2L)).thenReturn(broken);
        when(dataSourceManager.dialect(2L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        context.removeRouteDefinition(context.getRouteDefinition("job-13"));
        context.start();
        try {
            assertThatThrownBy(() ->
                    context.createProducerTemplate().requestBody("direct:job-13", Map.of("order_no", "PO-1")))
                    .isInstanceOf(Exception.class);
        } finally {
            context.stop();
        }

        assertThat(runs.values()).anyMatch(r -> r.getJobId().equals(13L) && "FAILED".equals(r.getStatus()));
        MessageStep failed = steps.values().stream()
                .filter(s -> "FAILED".equals(s.getStatus()) && s.getStep() == 2)
                .findFirst().orElseThrow();
        assertThat(failed.getActionName()).isEqualTo("upsert");
        assertThat(failed.getDurationMs())
                .as("#134：失败那一步也要有耗时（失败往往正是要看耗时的那一步）")
                .isNotNull();
    }

    /** #73 AC：成功链 每消息×每动作 一条 SUCCESS 步，同消息各步共享 message_id，businessKey 提取正确。 */
    @Test
    void successChainRecordsPerMessagePerActionWithSharedMessageIdAndBusinessKey() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(14L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of(
                        "dataSourceId", 1L, "queue", "bom.to.edge", "businessKey", "orderNo")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no"),
                        Map.of("sourceStep", 1, "source", "material", "target", "material")))),
                action(3, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));
        stubConsumeDataSource();

        // WRITE_UPSERT 用一个 H2(MySQL 模式) 内存库真正落库，验证成功链可全程记录
        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:msgHist;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (java.sql.Connection c = h2.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("create table if not exists po_receipt(order_no varchar(64) primary key, material varchar(64))");
        }
        when(dataSourceManager.get(2L)).thenReturn(h2);
        when(dataSourceManager.dialect(2L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        context.removeRouteDefinition(context.getRouteDefinition("job-14"));
        context.start();
        try {
            context.createProducerTemplate().requestBody("direct:job-14", Map.of("orderNo", "PO-1", "material", "M-1"));
        } finally {
            context.stop();
        }

        JobRun run = runs.values().stream()
                .filter(r -> r.getJobId().equals(14L))
                .findFirst()
                .orElse(null);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo("SUCCESS");

        List<MessageStep> runSteps2 = steps.values().stream()
                .filter(s -> s.getRunId().equals(run.getId()))
                .toList();
        assertThat(runSteps2).hasSize(2);
        assertThat(runSteps2).allMatch(s -> "SUCCESS".equals(s.getStatus()));
        assertThat(runSteps2).extracting(MessageStep::getMessageId)
                .containsOnly(runSteps2.get(0).getMessageId());
        assertThat(runSteps2).allMatch(s -> "PO-1".equals(s.getBusinessKey()));
        assertThat(runSteps2).extracting(MessageStep::getStep).containsExactlyInAnyOrder(2, 3);
    }

    /** #73 AC1 按用户意见：源动作 READ_SQL 也作为链上一步——每条源行记一条 SUCCESS 步（无输入快照）。 */
    @Test
    void readChainRecordsSourceActionStepPerMessage() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(15L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no, vendor_code from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "businessKey", "order_no")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 1, "source", "order_no", "target", "order_no"),
                        Map.of("sourceStep", 1, "source", "vendor_code", "target", "material")))),
                action(3, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 1L, "tableName", "po_receipt", "idempotencyKey", "order_no")));

        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:readHist;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (java.sql.Connection c = h2.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("create table po_order(id bigint primary key, order_no varchar(64), vendor_code varchar(64))");
            st.execute("create table po_receipt(order_no varchar(64) primary key, material varchar(64))");
            st.execute("insert into po_order(id, order_no, vendor_code) values (1,'PO-1','V1'), (2,'PO-2','V2')");
        }
        when(dataSourceManager.get(1L)).thenReturn(h2);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        // READ 的 sql: 组件按 `#edge-ds-1` bean 解析数据源（真实应用由 DataSourceManager 注册）
        context.getRegistry().bind("edge-ds-1", h2);

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        context.start();
        try {
            context.createProducerTemplate().requestBody("direct:job-15", Map.of());
        } finally {
            context.stop();
        }

        JobRun run = runs.values().stream()
                .filter(r -> r.getJobId().equals(15L))
                .findFirst()
                .orElse(null);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo("SUCCESS");

        List<MessageStep> all = steps.values().stream()
                .filter(s -> s.getRunId().equals(run.getId()))
                .toList();
        assertThat(all).hasSize(6); // 2 条源行 × 3 动作（read/map/upsert）

        Map<String, List<MessageStep>> byMsg = all.stream().collect(Collectors.groupingBy(MessageStep::getMessageId));
        assertThat(byMsg).hasSize(2);
        byMsg.forEach((messageId, list) -> {
            assertThat(list).hasSize(3);
            assertThat(list).allMatch(s -> "SUCCESS".equals(s.getStatus()));
            assertThat(list).extracting(MessageStep::getStep).containsExactlyInAnyOrder(1, 2, 3);
            assertThat(list).allMatch(s -> s.getBusinessKey() != null); // order_no 提取
            assertThat(list).allMatch(s -> s.getDurationMs() != null,
                    "#134：成功链的每一步都要有耗时，源动作 READ_SQL 那一步也在内");
            MessageStep readStep = list.stream().filter(s -> s.getStep() == 1).findFirst().orElseThrow();
            assertThat(readStep.getActionName()).isEqualTo("read");
            assertThat(readStep.getInputSnapshot()).isNull(); // 源动作无输入快照
        });

        // #134：读库只做了一次、落库却发生在每条源行上——两条源行的读步因此共用这一次读的耗时
        //（起点在 split 之前就结算了）。不这么做，第二条源行量到的是「读库 + 前一条消息跑完」。
        List<Long> readDurations = all.stream().filter(s -> s.getStep() == 1)
                .map(MessageStep::getDurationMs).distinct().toList();
        assertThat(readDurations).as("两条源行的读步共用一个耗时").hasSize(1);
        assertThat(readDurations.getFirst()).isNotNull();
    }

    /**
     * #134：**分批**读的时候每一批各量各的——第 2 批不能拿着第 1 批那个数。
     *
     * <p>起点与结算都挂在 exchange 属性上，起点要是打在循环外（`prepareRead` 那一处只跑一次），
     * 第二批起就量的是第一批读库的时间。这条用「一次比一次慢」的连接代理把批与批分开：
     * 三批的读耗时必须**递增**，全等于第 1 批那个数就是串味了。</p>
     */
    @Test
    void batchedReadTimesEveryBatchSeparately() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(16L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id",
                        "businessKey", "order_no", "batchSize", 1)),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 1, "source", "order_no", "target", "order_no")))));

        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:readBatchTiming;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        execute(h2, "create table po_order(id bigint primary key, order_no varchar(64))",
                "insert into po_order(id, order_no) values (1,'PO-1'), (2,'PO-2'), (3,'PO-3')");
        javax.sql.DataSource slow = new SlowerEachTimeDataSource(h2, 30L);
        when(dataSourceManager.get(1L)).thenReturn(slow);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        context.getRegistry().bind("edge-ds-1", slow);

        buildRoutes(context, history, job, actions);
        try {
            context.createProducerTemplate().requestBody("direct:job-16", Map.of());
        } finally {
            context.stop();
        }

        JobRun run = runs.values().stream().filter(r -> r.getJobId().equals(16L)).findFirst().orElse(null);
        assertThat(run).as("运行没起来: " + describeRuns(16L)).isNotNull();
        List<Long> readDurations = steps.values().stream()
                .filter(s -> s.getRunId().equals(run.getId()) && s.getStep() == 1)
                .sorted(java.util.Comparator.comparing(MessageStep::getId))
                .map(MessageStep::getDurationMs)
                .toList();

        assertThat(readDurations).as("三批各记一条读步 | " + readDurations).hasSize(3);
        assertThat(readDurations).doesNotContainNull();
        assertThat(readDurations.get(0)).as("第 2 批不能拿着第 1 批那个数 | " + readDurations)
                .isLessThan(readDurations.get(1));
        assertThat(readDurations.get(1)).as("第 3 批同理 | " + readDurations)
                .isLessThan(readDurations.get(2));
    }

    /**
     * #139：**批号逐行落库，并在链上继承到下一个基数变化动作为止**。
     *
     * <p>跑一条真链（READ_SQL 分批读 3 行、批大小 2 + FIELD_MAP），断言库里那几行消息步：</p>
     * <ul>
     *   <li>每一行都带当前批号（读取批也带——今天它只活在运行期的交换器属性里，一个字节都没落库）；</li>
     *   <li>**读库每读一批一个新号**：同批的两条（PO-1 / PO-2）同号，下一批（PO-3）换号；</li>
     *   <li>批号**继承**：同一条消息在下一个动作（FIELD_MAP）那一步还是同一个号——链上没有基数变化动作，
     *       所以从头到尾不换手（拆分 / 合并那两个动作才换，见 ADR-0017 决策 2、3）；</li>
     *   <li>批内序号与父消息标识**留空**：它们只在拆分与合并时写，读取批里「第几条」没有额外语义。</li>
     * </ul>
     */
    @Test
    void batchedReadStampsEveryRowWithItsBatchAndInheritsItDownTheChain() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(18L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id",
                        "businessKey", "order_no", "batchSize", 2)),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 1, "source", "order_no", "target", "order_no")))));

        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:readBatchStamp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        execute(h2, "create table po_order(id bigint primary key, order_no varchar(64))",
                "insert into po_order(id, order_no) values (1,'PO-1'), (2,'PO-2'), (3,'PO-3')");
        when(dataSourceManager.get(1L)).thenReturn(h2);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        context.getRegistry().bind("edge-ds-1", h2);

        buildRoutes(context, history, job, actions);
        try {
            context.createProducerTemplate().requestBody("direct:job-18", Map.of());
        } finally {
            context.stop();
        }

        JobRun run = runOf(18L);
        assertThat(run).as("运行没起来: " + describeRuns(18L)).isNotNull();
        List<MessageStep> all = stepsOf(run);
        assertThat(all).as("3 条源行 × 2 个动作 | " + describeSteps()).hasSize(6);
        assertThat(all).allSatisfy(step -> assertThat(step.getBatchId())
                .as("每一行消息步都带当前批号 | " + describeSteps()).isNotBlank());
        assertThat(all).allSatisfy(step -> assertThat(step.getBatchSeq())
                .as("批内序号只在拆分与合并时写，读取批留空").isNull());
        assertThat(all).allSatisfy(step -> assertThat(step.getParentMessageId())
                .as("这条链上没有拆分，父消息标识留空").isNull());

        Map<String, List<MessageStep>> byBusinessKey = all.stream()
                .collect(Collectors.groupingBy(MessageStep::getBusinessKey));
        assertThat(byBusinessKey.keySet()).containsExactlyInAnyOrder("PO-1", "PO-2", "PO-3");
        byBusinessKey.values().forEach(stepsOfOneMessage -> {
            assertThat(stepsOfOneMessage).hasSize(2);
            assertThat(stepsOfOneMessage).extracting(MessageStep::getBatchId)
                    .as("批号在链上继承：这条消息的每一步同号")
                    .containsOnly(stepsOfOneMessage.getFirst().getBatchId());
        });

        String firstBatch = byBusinessKey.get("PO-1").getFirst().getBatchId();
        assertThat(byBusinessKey.get("PO-2").getFirst().getBatchId())
                .as("同一读取批的两条消息共用一个批号").isEqualTo(firstBatch);
        assertThat(byBusinessKey.get("PO-3").getFirst().getBatchId())
                .as("读库每读一批一个新号：下一批换号 | " + describeSteps()).isNotEqualTo(firstBatch);
    }

    /**
     * #139：**失败那条路也带批号**——只记成功那一侧的话，「哪几条是同一批」在失败那一格上就断了，
     * 而失败的那一条往往正是要看的那一条。
     *
     * <p>批大小 2、共 3 行：第 1 批（PO-1 / PO-2）走通，第 2 批（PO-3）的 FIELD_MAP 抛错。
     * 第 2 批的读步与失败步必须是**同一个批号**（同一条消息、同一次读），而且与第 1 批不同号。</p>
     */
    @Test
    void failedStepInABatchCarriesThatBatchsId() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(19L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id",
                        "businessKey", "order_no", "batchSize", 2)),
                action(2, ActionKind.FIELD_MAP, Map.of("mode", "EXPRESSION",
                        "expression", "$s_1.id > 2 ? $error('boom on batch2') : $s_1.id")));

        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:readBatchFailStamp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        execute(h2, "create table po_order(id bigint primary key, order_no varchar(64))",
                "insert into po_order(id, order_no) values (1,'PO-1'), (2,'PO-2'), (3,'PO-3')");
        when(dataSourceManager.get(1L)).thenReturn(h2);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        context.getRegistry().bind("edge-ds-1", h2);

        buildRoutes(context, history, job, actions);
        try {
            assertThatThrownBy(() -> context.createProducerTemplate().requestBody("direct:job-19", Map.of()))
                    .as("失败链应向调用方抛异常").isInstanceOf(Exception.class);
        } finally {
            context.stop();
        }

        JobRun run = runOf(19L);
        assertThat(run).as("运行没起来: " + describeRuns(19L)).isNotNull();
        List<MessageStep> runSteps = stepsOf(run);
        MessageStep failed = runSteps.stream().filter(s -> "FAILED".equals(s.getStatus())).findFirst()
                .orElseThrow(() -> new AssertionError("没有 FAILED 步 | " + describeSteps()));
        MessageStep readOfSameMessage = runSteps.stream()
                .filter(s -> s.getStep() == 1 && s.getMessageId().equals(failed.getMessageId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("失败那条消息的读步不在 | " + describeSteps()));

        assertThat(failed.getBatchId()).as("失败步也带批号").isNotBlank();
        assertThat(failed.getBatchId()).as("同一条消息、同一次读：读步与失败步同一个号")
                .isEqualTo(readOfSameMessage.getBatchId());
        assertThat(runSteps.stream()
                .filter(s -> !s.getMessageId().equals(failed.getMessageId()))
                .map(MessageStep::getBatchId)
                .distinct()
                .toList())
                .as("失败那批的号与走通那批分开 | " + describeSteps())
                .doesNotContain(failed.getBatchId());
    }

    /**
     * #134：某一步**成功之后**、在步骤之外出的错（落运行 / 推水位那几处不在任何动作片段里），
     * 不该把「上一步开始到现在」算成那一步的耗时——那是个看着像模像样的错数。
     *
     * <p>记完那一步就把计时起点收掉，所以这里给的是 {@code null}（页面写 `—`），
     * 而不是「刚刚那一步的时间」。</p>
     */
    @Test
    void failureOutsideAnyStepDoesNotChargeThatStepsClock() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(17L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "bom.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))));
        stubConsumeDataSource();
        // 链尾写运行那一刻炸掉：那一步不属于任何动作片段，onException 会拿最后一步（2）的序号记 FAILED
        when(jobRunMapper.updateById(any(JobRun.class))).thenThrow(new RuntimeException("写运行失败"));

        buildRoutes(context, history, job, actions);
        try {
            assertThatThrownBy(() -> context.createProducerTemplate()
                    .requestBody("direct:job-17", Map.of("orderNo", "PO-1")))
                    .as("失败链应向调用方抛异常").isInstanceOf(Exception.class);
        } finally {
            context.stop();
        }

        JobRun run = runOf(17L);
        assertThat(run).as("运行没起来: " + describeRuns(17L)).isNotNull();
        MessageStep failed = stepsOf(run).stream().filter(s -> "FAILED".equals(s.getStatus()))
                .findFirst().orElseThrow(() -> new AssertionError("没有 FAILED 步 | " + describeSteps()));
        assertThat(failed.getStep()).isEqualTo(2);
        assertThat(failed.getDurationMs())
                .as("不在任何一步里出的错，不该背上那一步的时钟 | " + describeSteps())
                .isNull();
    }

    /** #73 按用户意见：READ_SQL 源失败也应记一条 FAILED 消息步（用批量占位消息 id，无输入快照）。 */
    @Test
    void failedReadRecordsFailedSourceStep() throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = service(context);
        bindInMemory();
        Job job = job(16L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select * from no_such_table where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id")));

        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:failRead;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        when(dataSourceManager.get(1L)).thenReturn(h2);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        context.getRegistry().bind("edge-ds-1", h2);

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        context.start();
        try {
            assertThatThrownBy(() ->
                    context.createProducerTemplate().requestBody("direct:job-16", Map.of()))
                    .isInstanceOf(Exception.class);
        } finally {
            context.stop();
        }

        assertThat(runs.values()).anyMatch(r -> r.getJobId().equals(16L) && "FAILED".equals(r.getStatus()));
        assertThat(steps.values()).anyMatch(s -> "FAILED".equals(s.getStatus())
                && s.getStep() == 1 && "read".equals(s.getActionName()));
    }

    /** 断言失败时附上内存表状态，便于定位"没跑到的步骤"（这些用例依赖真实 Camel 链的执行顺序）。 */
    private String describeRuns(Long jobId) {
        return runs.values().stream()
                .filter(r -> jobId.equals(r.getJobId()))
                .map(r -> r.getId() + "/" + r.getStatus() + "/" + r.getError())
                .toList().toString();
    }

    // ---------- #123 用例的脚手架：本地 HTTP 替身 + H2 目标库 + 内存表取数 ----------

    /**
     * 建路由并启动上下文。链首是 MQ_CONSUME 时摘掉真消费路由（测试里不连 broker），
     * 入口改用重放路由 {@code direct:job-<id>}——与既有用例同一手法。
     */
    private void buildRoutes(DefaultCamelContext context, MessageHistoryService history, Job job,
                             List<Action> actions) throws Exception {
        RouteGenerator generator = new RouteGenerator(
                dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        if (actions.getFirst().getType() == ActionKind.MQ_CONSUME) {
            context.removeRouteDefinition(context.getRouteDefinition("job-" + job.getId()));
        }
        context.start();
    }

    /**
     * 起一个本地 HTTP 替身：状态码与报文由 {@link #httpStatus}/{@link #httpBody} 现场控制，
     * 调用次数记在 {@link #httpCalls}（断言"该步没被重新调用"）。用完必须 {@code stop(0)}。
     */
    private HttpServer startHttpTarget() throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            httpCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String payload = httpBody.get();
            byte[] body = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private void stubHttpDataSource(Long id, HttpServer server) {
        DataSource ds = new DataSource();
        ds.setId(id);
        ds.setType(DataSourceType.HTTP);
        ds.setAddress("http://127.0.0.1:" + server.getAddress().getPort());
        ds.setAddition(Map.of());
        when(dataSourceMapper.selectById(id)).thenReturn(ds);
    }

    private void execute(javax.sql.DataSource ds, String... sql) throws SQLException {
        try (java.sql.Connection connection = ds.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            for (String each : sql) {
                statement.execute(each);
            }
        }
    }

    /**
     * 每取一次连接就比上一次多睡 {@code stepMs} 的数据源（#134 的分批计时用例要它）：
     * 批与批的读耗时因此**递增**，「第 2 批拿着第 1 批那个数」这种串味一眼看得出来。
     */
    private static final class SlowerEachTimeDataSource
            extends org.springframework.jdbc.datasource.DelegatingDataSource {

        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        private final long stepMs;

        private SlowerEachTimeDataSource(javax.sql.DataSource target, long stepMs) {
            super(target);
            this.stepMs = stepMs;
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            try {
                Thread.sleep(stepMs * calls.incrementAndGet());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.getConnection();
        }
    }

    /** 单值查询：无行时返回 null（断言"没写回"用）。 */
    private String queryString(javax.sql.DataSource ds, String sql) {
        List<String> rows = new JdbcTemplate(ds).queryForList(sql, String.class);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private JobRun runOf(Long jobId) {
        return runs.values().stream().filter(r -> jobId.equals(r.getJobId())).findFirst().orElse(null);
    }

    private List<MessageStep> stepsOf(JobRun run) {
        return steps.values().stream().filter(s -> s.getRunId().equals(run.getId())).toList();
    }

    private MessageStep stepOf(List<MessageStep> runSteps, int index) {
        return runSteps.stream().filter(s -> s.getStep() == index).findFirst()
                .orElseThrow(() -> new AssertionError("没有步骤 " + index + " | steps=" + describeSteps()));
    }

    private String describeSteps() {
        return steps.values().stream()
                .map(s -> s.getRunId() + ":" + s.getStep() + "/" + s.getStatus() + "/" + s.getMessageId())
                .toList().toString();
    }

    private void bindInMemory() {
        // 重投前的源动作判定要查动作表（LambdaQueryWrapper）——不桩就 NPE，且只有"重投真的抛错"时才暴露
        when(actionMapper.selectList(any())).thenReturn(List.of());
        when(jobRunMapper.insert(any(JobRun.class))).thenAnswer(inv -> {
            JobRun run = inv.getArgument(0);
            run.setId(runSeq++);
            runs.put(run.getId(), run);
            return 1;
        });
        when(jobRunMapper.selectById(anyLong())).thenAnswer(inv -> runs.get((Long) inv.getArgument(0)));
        when(jobRunMapper.updateById(any(JobRun.class))).thenAnswer(inv -> {
            JobRun run = inv.getArgument(0);
            runs.put(run.getId(), run);
            return 1;
        });
        when(jobRunMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(runs.values()));
        when(messageStepMapper.insert(any(MessageStep.class))).thenAnswer(inv -> {
            MessageStep step = inv.getArgument(0);
            step.setId(stepSeq++);
            steps.put(step.getId(), step);
            return 1;
        });
        when(messageStepMapper.selectById(anyLong())).thenAnswer(inv -> steps.get((Long) inv.getArgument(0)));
        // 内存表查询：按当前内存内容返回，上游筛选（runId/messageId/step<resumeFrom/SUCCESS）由被测代码自行完成
        when(messageStepMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(steps.values()));
        when(messageStepMapper.updateById(any(MessageStep.class))).thenAnswer(inv -> {
            MessageStep step = inv.getArgument(0);
            steps.put(step.getId(), step);
            return 1;
        });
        when(messageStepMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<MessageStep> page = inv.getArgument(0);
            page.setRecords(new ArrayList<>(steps.values()));
            page.setTotal(steps.size());
            return page;
        });
    }

    private void stubConsumeDataSource() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setType(DataSourceType.RABBITMQ);
        ds.setName("mes.exchange");
        ds.setAddition(Map.of("host", "localhost", "port", 5672));
        when(dataSourceMapper.selectById(1L)).thenReturn(ds);
    }

    private Job job(Long id) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(1);
        return job;
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setName(switch (kind) {
            case READ_SQL -> "read";
            case FIELD_MAP -> "map";
            case WRITE_UPSERT -> "upsert";
            case HTTP_CALL -> "call";
            case MQ_CONSUME -> "consume";
            case MQ_PUBLISH -> "publish";
            case SPLIT -> "split";
        });
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }
}
