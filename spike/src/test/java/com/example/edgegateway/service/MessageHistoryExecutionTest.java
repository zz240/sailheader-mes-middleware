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
        assertThat(steps.values()).anyMatch(s -> "FAILED".equals(s.getStatus())
                && s.getStep() == 2 && "upsert".equals(s.getActionName()));
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
            MessageStep readStep = list.stream().filter(s -> s.getStep() == 1).findFirst().orElseThrow();
            assertThat(readStep.getActionName()).isEqualTo("read");
            assertThat(readStep.getInputSnapshot()).isNull(); // 源动作无输入快照
        });
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

    private String describeSteps() {
        return steps.values().stream()
                .map(s -> s.getRunId() + ":" + s.getStep() + "/" + s.getStatus() + "/" + s.getMessageId())
                .toList().toString();
    }

    private void bindInMemory() {
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
        });
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }
}
