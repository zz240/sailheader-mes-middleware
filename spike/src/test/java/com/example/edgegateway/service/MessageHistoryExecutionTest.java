package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

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

    private final DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
    private final WatermarkStore watermarkStore = mock(WatermarkStore.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);

    private final Map<Long, JobRun> runs = new ConcurrentHashMap<>();
    private final Map<Long, MessageStep> steps = new ConcurrentHashMap<>();

    private long runSeq = 1;
    private long stepSeq = 1;

    private MessageHistoryService service(DefaultCamelContext context) {
        MessageHistoryService s = new MessageHistoryService(
                jobRunMapper, messageStepMapper, jobMapper, new ObjectMapper(), context);
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

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager);
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

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager);
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

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager);
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

        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history, dataSourceManager);
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
