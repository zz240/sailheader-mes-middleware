package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.BatchStamp;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.model.MessageStepIndex;
import com.example.edgegateway.model.RunStepSummary;
import com.example.edgegateway.model.RunStepTotals;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageHistoryServiceTest {

    @BeforeAll
    static void initEntityTableInfo() {
        // 纯 Mockito 单测无 Spring 扫描，需显式初始化 MyBatis-Plus 实体表/列缓存，
        // 否则 LambdaQueryWrapper 独立运行（未先跑 @MybatisPlusTest）会抛 "can not find lambda cache"。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageStep.class);
        TableInfoHelper.initTableInfo(assistant, JobRun.class);
        TableInfoHelper.initTableInfo(assistant, Action.class);
    }

    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final CamelContext camelContext = mock(CamelContext.class);
    private final MessageHistoryService service = new MessageHistoryService(
            jobRunMapper, messageStepMapper, jobMapper, actionMapper, new ObjectMapper(), camelContext,
            new JobLastRunWriter(jobMapper));

    @Test
    void startRunInsertsRunningRun() {
        when(jobRunMapper.insert(any(JobRun.class))).thenReturn(1);

        JobRun run = service.startRun(7L, Map.of("orderNo", "PO-1"));

        assertThat(run.getJobId()).isEqualTo(7L);
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getInputSnapshot()).contains("PO-1");
        verify(jobRunMapper).insert(any(JobRun.class));
    }

    @Test
    void finishRunMarksSuccessOnlyWhenRunning() {
        JobRun run = new JobRun();
        run.setId(3L);
        run.setStatus("RUNNING");
        when(jobRunMapper.selectById(3L)).thenReturn(run);

        service.finishRun(3L, "SUCCESS", null);

        assertThat(run.getStatus()).isEqualTo("SUCCESS");
        verify(jobRunMapper).updateById(run);
    }

    @Test
    void finishRunSkipsAlreadyFinishedRun() {
        JobRun run = new JobRun();
        run.setId(3L);
        run.setStatus("FAILED");
        when(jobRunMapper.selectById(3L)).thenReturn(run);

        service.finishRun(3L, "SUCCESS", null);

        verify(jobRunMapper, never()).updateById(any(JobRun.class));
    }

    @Test
    void recordSuccessInsertsMessageStep() {
        when(messageStepMapper.selectOne(any())).thenReturn(null);

        service.recordSuccess(3L, "msg-1", 2, "map", Map.of("orderNo", "PO-1"),
                Map.of("orderNo", "PO-1", "sheet_no", "S-1"), "PO-1", 1, 12L,
                new BatchStamp("batch-1", 2, "parent-1"));

        ArgumentCaptor<MessageStep> captor = ArgumentCaptor.forClass(MessageStep.class);
        verify(messageStepMapper).insert(captor.capture());
        MessageStep step = captor.getValue();
        assertThat(step.getRunId()).isEqualTo(3L);
        assertThat(step.getMessageId()).isEqualTo("msg-1");
        assertThat(step.getStep()).isEqualTo(2);
        assertThat(step.getActionName()).isEqualTo("map");
        assertThat(step.getStatus()).isEqualTo("SUCCESS");
        assertThat(step.getBusinessKey()).isEqualTo("PO-1");
        assertThat(step.getDurationMs()).as("成功步的动作耗时落库（#134）").isEqualTo(12L);
        assertThat(step.getBatchId()).as("成功那条路也写批号（#139）").isEqualTo("batch-1");
        assertThat(step.getBatchSeq()).isEqualTo(2);
        assertThat(step.getParentMessageId()).isEqualTo("parent-1");
    }

    /** #134：量不出来时是**空**，不是 0——「没测」与「测得就是 0 ms」在页面上是两件事（写 `—` / 写 `0 ms`）。 */
    @Test
    void recordWithoutMeasuredDurationLeavesItNull() {
        when(messageStepMapper.selectOne(any())).thenReturn(null);

        service.recordSuccess(3L, "msg-1", 2, "map", Map.of("orderNo", "PO-1"),
                Map.of("orderNo", "PO-1"), "PO-1", 1, null, BatchStamp.NONE);

        ArgumentCaptor<MessageStep> captor = ArgumentCaptor.forClass(MessageStep.class);
        verify(messageStepMapper).insert(captor.capture());
        assertThat(captor.getValue().getDurationMs()).isNull();
    }

    @Test
    void recordFailureUpdatesExistingMessageStep() {
        MessageStep existing = new MessageStep();
        existing.setId(9L);
        existing.setStatus("SUCCESS");
        when(messageStepMapper.selectOne(any())).thenReturn(existing);

        service.recordFailure(3L, "msg-1", 3, "upsert", "boom", Map.of("orderNo", "PO-1"), "PO-1", 1, 34L,
                new BatchStamp("batch-1", null, null));

        assertThat(existing.getStatus()).isEqualTo("FAILED");
        assertThat(existing.getError()).isEqualTo("boom");
        assertThat(existing.getDurationMs()).as("失败步也记耗时（#134）").isEqualTo(34L);
        assertThat(existing.getBatchId()).as("失败那条路也写批号（#139）").isEqualTo("batch-1");
        assertThat(existing.getBatchSeq()).as("读取批没有批内序号：空就是空，不编 0").isNull();
        verify(messageStepMapper).updateById(existing);
    }

    @Test
    void listStepsFiltersByBusinessKey() {
        JobRun run = new JobRun();
        run.setId(3L);
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run));
        MessageStep step = new MessageStep();
        when(messageStepMapper.selectPage(any(), any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<MessageStep> page = inv.getArgument(0);
            page.setRecords(List.of(step));
            page.setTotal(1);
            return page;
        });

        Map<String, Object> result = service.listSteps(7L, null, null, "PO-9", "FAILED", 1, 20);

        assertThat(result.get("total")).isEqualTo(1L);
        assertThat((List<?>) result.get("records")).hasSize(1);
    }

    /**
     * #136：列表按页补上每步所属运行的 jobId（左列表的消息步行要写「任务 #id」）。
     * 一次 in 查询补完——补 jobId 不是「每行一次」，前端那边更不是 N+1。
     */
    @Test
    void listStepsCarriesRunJobIdInOneBatch() {
        JobRun run = new JobRun();
        run.setId(3L);
        run.setJobId(7L);
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run));
        MessageStep step = new MessageStep();
        step.setId(1L);
        step.setRunId(3L);
        when(messageStepMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<MessageStep> page = inv.getArgument(0);
            page.setRecords(List.of(step));
            page.setTotal(1);
            return page;
        });

        Map<String, Object> result = service.listSteps(null, null, null, null, "FAILED", 1, 20);

        List<?> records = (List<?>) result.get("records");
        assertThat(records).hasSize(1);
        assertThat(((MessageStep) records.get(0)).getJobId()).isEqualTo(7L);
        verify(jobRunMapper, times(1)).selectList(any());
    }

    /** 运行查不到（脏数据）时 jobId 留空，界面写「任务 —」，而不是编一个 id 出来。 */
    @Test
    void listStepsLeavesJobIdEmptyWhenRunMissing() {
        when(jobRunMapper.selectList(any())).thenReturn(List.of());
        MessageStep step = new MessageStep();
        step.setId(1L);
        step.setRunId(3L);
        when(messageStepMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<MessageStep> page = inv.getArgument(0);
            page.setRecords(List.of(step));
            page.setTotal(1);
            return page;
        });

        Map<String, Object> result = service.listSteps(null, null, null, null, "FAILED", 1, 20);

        List<?> records = (List<?>) result.get("records");
        assertThat(((MessageStep) records.get(0)).getJobId()).isNull();
    }

    /**
     * #131：一条运行的消息步索引按页取，runId 原样下推给那条投影查询（不把整库的步拉回来再筛）。
     */
    @Test
    void runStepIndexPagesByRun() {
        JobRun run = new JobRun();
        run.setId(3L);
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run));
        MessageStepIndex row = new MessageStepIndex();
        row.setId(1L);
        when(messageStepMapper.pageRunStepIndex(any(), any())).thenAnswer(inv -> {
            Page<MessageStepIndex> page = inv.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        });

        Map<String, Object> result = service.runStepIndex(3L, 2, 500);

        assertThat(result.get("total")).isEqualTo(1L);
        assertThat((List<?>) result.get("records")).hasSize(1);
        verify(messageStepMapper).pageRunStepIndex(any(), eq(3L));
    }

    /**
     * 运行不存在时给 404（与 getRun 同一个口径）：界面上只会拿列表里的 id 来问，
     * 真问到不存在的 id 是调用方错了——安静地回一个空列表会把「运行没了」装成「这条运行没有步」。
     */
    @Test
    void runStepIndexRejectsMissingRun() {
        when(jobRunMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.runStepIndex(404L, 1, 500))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("404");

        verify(messageStepMapper, never()).pageRunStepIndex(any(), any());
    }

    @Test
    void redeliverMarksFailedStepAsRedeliveredOnSuccess() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(1L);
        step.setRunId(3L);
        step.setStep(4);
        step.setStatus("FAILED");
        step.setInputSnapshot("{\"orderNo\":\"PO-1\"}");
        when(messageStepMapper.selectById(1L)).thenReturn(step);
        JobRun run = new JobRun();
        run.setId(3L);
        run.setJobId(7L);
        when(jobRunMapper.selectById(3L)).thenReturn(run);
        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        when(jobMapper.selectById(7L)).thenReturn(job);
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(camelContext.createProducerTemplate()).thenReturn(producer);

        MessageStep result = service.redeliver(1L);

        assertThat(result.getStatus()).isEqualTo("REDELIVERED");
        verify(messageStepMapper).updateById(step);
    }

    @Test
    void redeliverCarriesBusinessKeyIntoResumeExchange() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(1L);
        step.setRunId(3L);
        step.setStep(4);
        step.setStatus("FAILED");
        step.setInputSnapshot("{\"orderNo\":\"PO-1\"}");
        step.setBusinessKey("PO-1");
        when(messageStepMapper.selectById(1L)).thenReturn(step);
        JobRun run = new JobRun();
        run.setId(3L);
        run.setJobId(7L);
        when(jobRunMapper.selectById(3L)).thenReturn(run);
        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        when(jobMapper.selectById(7L)).thenReturn(job);
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(camelContext.createProducerTemplate()).thenReturn(producer);
        ArgumentCaptor<Processor> captor = ArgumentCaptor.forClass(Processor.class);
        when(producer.request(anyString(), captor.capture())).thenReturn(null);

        service.redeliver(1L);

        Exchange ex = new DefaultExchange(camelContext);
        captor.getValue().process(ex);
        assertThat(ex.getProperty(MessageHistoryService.BUSINESS_KEY)).isEqualTo("PO-1");
        assertThat(ex.getProperty(MessageHistoryService.RESUME_FROM)).isEqualTo(4);
    }

    /**
     * #139：重投把原步的**批次戳**沿进续跑 exchange——重投是同一个消息步的重跑，它属于哪一批、
     * 从哪条消息来都不变；断点续跑路由跳过了读库那一步，批号不会现生成，不沿下来那几行的
     * `batch_id` 就是空的（右详情会把一条在某批里的链画成「单条 · 不是一批」）。
     */
    @Test
    void redeliverCarriesBatchStampIntoResumeExchange() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(1L);
        step.setRunId(3L);
        step.setStep(4);
        step.setStatus("FAILED");
        step.setInputSnapshot("{\"orderNo\":\"PO-1\"}");
        step.setBatchId("batch-A");
        step.setBatchSeq(2);
        step.setParentMessageId("parent-1");
        when(messageStepMapper.selectById(1L)).thenReturn(step);
        JobRun run = new JobRun();
        run.setId(3L);
        run.setJobId(7L);
        when(jobRunMapper.selectById(3L)).thenReturn(run);
        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        when(jobMapper.selectById(7L)).thenReturn(job);
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(camelContext.createProducerTemplate()).thenReturn(producer);
        ArgumentCaptor<Processor> captor = ArgumentCaptor.forClass(Processor.class);
        when(producer.request(anyString(), captor.capture())).thenReturn(null);

        service.redeliver(1L);

        Exchange ex = new DefaultExchange(camelContext);
        captor.getValue().process(ex);
        assertThat(ex.getProperty(MessageHistoryService.BATCH_MSG_ID)).isEqualTo("batch-A");
        assertThat(ex.getProperty(MessageHistoryService.BATCH_SEQ)).isEqualTo(2);
        assertThat(ex.getProperty(MessageHistoryService.PARENT_MESSAGE_ID)).isEqualTo("parent-1");
    }

    /**
     * #90：断点重投把同一消息下更早的 SUCCESS 步骤输出当上下文投给 resume 入口，
     * 被跳步骤引用的映射才能续跑。查询条件（run/message/step&lt;resumeFrom/SUCCESS）由 MyBatis-Plus 下推数据库，
     * 这里给的是该查询的结果集：断言的是服务自身的组装语义——按序号升序、跳过无快照步骤、快照 JSON 解析成对象。
     */
    @Test
    void redeliverCarriesUpstreamSuccessSnapshotsAsResumeContext() throws Exception {
        // 查询结果（乱序）：step=6 无快照（如被跳步）→ 不注入该键
        stubRedeliver(failedStep(7), List.of(
                upstreamStep(3, "SUCCESS", "{\"order_no\":\"PO-1\",\"sheet_no\":\"S-1\"}"),
                upstreamStep(6, "SUCCESS", null),
                upstreamStep(1, "SUCCESS", "{\"order_no\":\"PO-1\",\"vendor\":\"V1\"}")));
        ArgumentCaptor<Processor> captor = captureResumeProcessor();

        service.redeliver(1L);

        Exchange ex = new DefaultExchange(camelContext);
        captor.getValue().process(ex);
        Map<Integer, Object> expected = new LinkedHashMap<>();
        expected.put(1, Map.of("order_no", "PO-1", "vendor", "V1"));
        expected.put(3, Map.of("order_no", "PO-1", "sheet_no", "S-1"));
        assertThat(ex.getProperty(MessageHistoryService.RESUME_CONTEXT)).isEqualTo(expected);
    }

    /** #90 边界：上游步骤都没有快照（如被跳步/失败）时不注入任何键，行为与现状一致。 */
    @Test
    void redeliverCarriesNoResumeContextWhenUpstreamHasNoSnapshot() throws Exception {
        stubRedeliver(failedStep(2), List.of(upstreamStep(1, "SUCCESS", null)));
        ArgumentCaptor<Processor> captor = captureResumeProcessor();

        service.redeliver(1L);

        Exchange ex = new DefaultExchange(camelContext);
        captor.getValue().process(ex);
        assertThat(ex.getProperty(MessageHistoryService.RESUME_CONTEXT)).isEqualTo(Map.of());
    }

    /** #90 边界：无步骤序号的消息步不查上游、不注入，且不影响重投本身。 */
    @Test
    void redeliverWithoutStepIndexCarriesEmptyResumeContext() throws Exception {
        stubRedeliver(failedStep(null), List.of(upstreamStep(1, "SUCCESS", "{\"order_no\":\"PO-1\"}")));
        ArgumentCaptor<Processor> captor = captureResumeProcessor();

        service.redeliver(1L);

        Exchange ex = new DefaultExchange(camelContext);
        captor.getValue().process(ex);
        assertThat(ex.getProperty(MessageHistoryService.RESUME_CONTEXT)).isEqualTo(Map.of());
        assertThat(ex.getProperty(MessageHistoryService.RESUME_FROM)).isNull();
    }

    /**
     * #90 + #123：上游快照按 JSON 值还原——对象 / 数组 / 标量都回填（HTTP 响应可以是数组或标量，
     * 只认对象会把这类响应在续跑时静默丢掉）；只有**非 JSON 文本**不进上下文（与一段乱码无从区分），
     * 且绝不因此阻断整次重投。
     */
    @Test
    void redeliverCarriesUpstreamSnapshotsOfAnyJsonShapeButSkipsPlainText() throws Exception {
        stubRedeliver(failedStep(4), List.of(
                upstreamStep(1, "SUCCESS", "[{\"order_no\":\"PO-1\"}]"),
                upstreamStep(2, "SUCCESS", "plain text, not json"),
                upstreamStep(3, "SUCCESS", "{\"order_no\":\"PO-1\"}")));
        ArgumentCaptor<Processor> captor = captureResumeProcessor();

        service.redeliver(1L);

        Exchange ex = new DefaultExchange(camelContext);
        captor.getValue().process(ex);
        Map<Integer, Object> expected = new LinkedHashMap<>();
        expected.put(1, List.of(Map.of("order_no", "PO-1")));
        expected.put(3, Map.of("order_no", "PO-1"));
        assertThat(ex.getProperty(MessageHistoryService.RESUME_CONTEXT)).isEqualTo(expected);
    }

    /**
     * #123 口径 4：捕获内容（HTTP 响应，大小由目标端决定）装不下快照列时**显式失败**，不写半截 JSON——
     * 写半截的话，断点续投会拿着一个语法都不完整的上游快照去重建 context，报出来的错离现场十万八千里。
     * 列容量本身归 #127。
     */
    @Test
    void oversizedCaptureFailsExplicitlyInsteadOfWritingHalfSnapshot() {
        String tooBig = "x".repeat(MessageHistoryService.SNAPSHOT_LIMIT_BYTES + 1);

        assertThatThrownBy(() -> service.requireCaptureFitsSnapshot(3, tooBig))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data_snapshot")
                .hasMessageContaining(String.valueOf(MessageHistoryService.SNAPSHOT_LIMIT_BYTES));
    }

    /** 容量边界：正好等于上限放行（合法快照不该被拒），空响应体（null）照常。 */
    @Test
    void captureAtTheCapacityLimitIsAccepted() {
        service.requireCaptureFitsSnapshot(3, "x".repeat(MessageHistoryService.SNAPSHOT_LIMIT_BYTES));
        service.requireCaptureFitsSnapshot(3, null);
    }

    /**
     * 这道闸只给捕获内容：其余动作的快照行为一概不变（超容量时照旧写库报 Data too long、动作失败）。
     * 把列本身改大、或让这些也显式失败，都归 #127，不在本票内。
     */
    @Test
    void otherActionSnapshotsKeepTheirOldBehaviour() {
        String tooBig = "x".repeat(MessageHistoryService.SNAPSHOT_LIMIT_BYTES + 1);

        service.recordSuccess(1L, "msg-1", 2, "map", Map.of("FCode", "C-1"), tooBig, "C-1", 1, null,
                BatchStamp.NONE);

        ArgumentCaptor<MessageStep> captor = ArgumentCaptor.forClass(MessageStep.class);
        verify(messageStepMapper).insert(captor.capture());
        assertThat(captor.getValue().getDataSnapshot()).hasSize(MessageHistoryService.SNAPSHOT_LIMIT_BYTES + 1);
    }

    private MessageStep failedStep(Integer step) {
        MessageStep failed = new MessageStep();
        failed.setId(1L);
        failed.setRunId(3L);
        failed.setMessageId("msg-1");
        failed.setStep(step);
        failed.setStatus("FAILED");
        failed.setInputSnapshot("{\"order_no\":\"PO-1\"}");
        when(messageStepMapper.selectById(1L)).thenReturn(failed);
        return failed;
    }

    private void stubRedeliver(MessageStep failed, List<MessageStep> upstreamQueryResult) {
        JobRun run = new JobRun();
        run.setId(3L);
        run.setJobId(7L);
        when(jobRunMapper.selectById(3L)).thenReturn(run);
        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        when(jobMapper.selectById(7L)).thenReturn(job);
        when(messageStepMapper.selectList(any())).thenReturn(upstreamQueryResult);
    }

    private ArgumentCaptor<Processor> captureResumeProcessor() {
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(camelContext.createProducerTemplate()).thenReturn(producer);
        ArgumentCaptor<Processor> captor = ArgumentCaptor.forClass(Processor.class);
        when(producer.request(anyString(), captor.capture())).thenReturn(null);
        return captor;
    }

    private MessageStep upstreamStep(int step, String status, String dataSnapshot) {
        MessageStep upstream = new MessageStep();
        upstream.setRunId(3L);
        upstream.setMessageId("msg-1");
        upstream.setStep(step);
        upstream.setStatus(status);
        upstream.setDataSnapshot(dataSnapshot);
        return upstream;
    }

    @Test
    void redeliverRejectsNonFailedStep() {
        MessageStep step = new MessageStep();
        step.setId(2L);
        step.setStatus("SUCCESS");
        when(messageStepMapper.selectById(2L)).thenReturn(step);

        assertThatThrownBy(() -> service.redeliver(2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅失败");
    }

    @Test
    void redeliverRejectsAlreadyRedeliveredStep() {
        MessageStep step = new MessageStep();
        step.setId(6L);
        step.setStatus("REDELIVERED");
        when(messageStepMapper.selectById(6L)).thenReturn(step);

        assertThatThrownBy(() -> service.redeliver(6L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅失败");
    }

    @Test
    void redeliverRejectsSourceActionStep() {
        MessageStep step = new MessageStep();
        step.setId(7L);
        step.setRunId(3L);
        step.setStep(1);
        step.setStatus("FAILED");
        step.setInputSnapshot("{}");
        when(messageStepMapper.selectById(7L)).thenReturn(step);
        JobRun run = new JobRun();
        run.setId(3L);
        run.setJobId(7L);
        when(jobRunMapper.selectById(3L)).thenReturn(run);
        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        when(jobMapper.selectById(7L)).thenReturn(job);
        Action source = new Action();
        source.setType(ActionKind.READ_SQL);
        when(actionMapper.selectList(any())).thenReturn(List.of(source));

        assertThatThrownBy(() -> service.redeliver(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("源动作");
    }

    @Test
    void redeliverThrowsWhenExecutionFails() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(5L);
        step.setRunId(9L);
        step.setStatus("FAILED");
        step.setInputSnapshot("{\"orderNo\":\"PO-1\"}");
        when(messageStepMapper.selectById(5L)).thenReturn(step);
        JobRun run = new JobRun();
        run.setId(9L);
        run.setJobId(11L);
        when(jobRunMapper.selectById(9L)).thenReturn(run);
        Job job = new Job();
        job.setId(11L);
        job.setStatus(1);
        when(jobMapper.selectById(11L)).thenReturn(job);
        ProducerTemplate producer = mock(ProducerTemplate.class);
        when(camelContext.createProducerTemplate()).thenReturn(producer);
        when(producer.request(anyString(), any())).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.redeliver(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重投失败");
        assertThat(step.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void getStepThrowsWhenMissing() {
        when(messageStepMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.getStep(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** #129：运行列表按业务键筛——命中的运行 id 从消息步里挑出来。 */
    @Test
    void listRunsKeepsRunsWhoseMessageMatchesBusinessKey() {
        JobRun run = new JobRun();
        run.setId(3L);
        run.setJobId(7L);
        run.setStatus("FAILED");
        Page<JobRun> page = new Page<>(1, 20);
        page.setRecords(List.of(run));
        page.setTotal(1L);
        when(jobRunMapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> result = service.listRuns(null, null, "SO-2026-0917-0042", 1, 20);

        assertThat(result.get("total")).isEqualTo(1L);
        assertThat((List<?>) result.get("records")).hasSize(1);
    }

    /**
     * #130 / ADR-0016 结果 ⑥：业务键筛选收成**一次查询**（命中判据下推成 EXISTS 子查询）——
     * 旧实现「先 LIKE 扫 message_step 拿 runId、再 IN 筛运行」在命中很多运行时是个无界 IN。
     * 所以这里钉住：不再单独扫一遍 message_step，分页查询照发。
     */
    @Test
    void listRunsPushesBusinessKeyFilterIntoOneQuery() {
        Page<JobRun> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(jobRunMapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> result = service.listRuns(null, null, "SO-2026-0917-0042", 1, 20);

        assertThat(result.get("total")).isEqualTo(0L);
        verify(jobRunMapper).selectPage(any(), any());
        verify(messageStepMapper, never()).selectList(any());
    }

    /**
     * #130：计数条与每运行摘要一条接口给全。四个计数是**整个筛选集**的（不含分页），
     * 每运行摘要只给这一页的行——两件事共用同一组筛选，所以数字一行一列都对得上。
     */
    @Test
    void runsSummaryCountsRunsMessagesStepsAndFailures() {
        JobRun failed = runOf(2L, "FAILED");
        JobRun ok = runOf(1L, "SUCCESS");
        when(jobRunMapper.selectCount(any())).thenReturn(2L);
        // 摘要用的那一页：主键倒序（与运行列表同一顺序）
        stubPage(List.of(failed, ok));
        when(messageStepMapper.summarizeByRunIds(List.of(2L, 1L))).thenReturn(List.of(
                summary(2L, 4, 3, 2),
                summary(1L, 2, 0, 1)));
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(6, 3, 3));

        Map<String, Object> result = service.runsSummary(7L, null, null, 1, 20);

        assertThat(result.get("runs")).isEqualTo(2L);
        assertThat(result.get("messages")).isEqualTo(3L);
        assertThat(result.get("steps")).isEqualTo(6L);
        assertThat(result.get("failedSteps")).isEqualTo(3L);
        List<?> summaries = (List<?>) result.get("summaries");
        assertThat(summaries).hasSize(2);
        assertThat(asMap(summaries.get(0)))
                .containsEntry("runId", 2L)
                .containsEntry("messageCount", 2L)
                .containsEntry("stepCount", 4L)
                .containsEntry("failedSteps", 3L);
        // 没筛业务键时那一格是空的（不把每条运行的全部业务键都塞回来）
        assertThat(asMap(summaries.get(0))).containsEntry("matchedBusinessKeys", List.of());
    }

    /**
     * #130 票面「翻页不影响计数」：换一页只换摘要给哪一批运行，四个计数照旧是整个筛选集的。
     * 第 2 页只回一行摘要，而计数仍是整条筛选集的总和。
     */
    @Test
    void runsSummaryCountsIgnorePaging() {
        JobRun second = runOf(1L, "SUCCESS");
        when(jobRunMapper.selectCount(any())).thenReturn(2L);
        stubPage(List.of(second));
        when(messageStepMapper.summarizeByRunIds(any())).thenReturn(List.of(summary(1L, 2, 1, 1)));
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(2, 1, 1));

        Map<String, Object> result = service.runsSummary(null, null, null, 2, 1);

        assertThat(result.get("runs")).isEqualTo(2L);
        assertThat(result.get("steps")).isEqualTo(2L);
        assertThat(result.get("failedSteps")).isEqualTo(1L);
        assertThat((List<?>) result.get("summaries")).hasSize(1);
    }

    /**
     * #130 口径：业务键那一格传的是 `%关键词%`，两侧空白不算（与列表同一个模式串形状）。
     *
     * <p>刻意**不转义** `%` / `_`：消息步检索那一侧用的是 MyBatis-Plus 的 `like()`，也不转义；
     * 只在这一侧加转义，同一个业务键会在两个模式里筛出不同的集合（审查抓到的口径分叉）。</p>
     */
    @Test
    void businessKeyPatternIsPlainLike() {
        when(jobRunMapper.selectCount(any())).thenReturn(0L);
        stubPage(List.of());
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(0, 0, 0));

        service.runsSummary(7L, "FAILED", "  SO_2026  ", 1, 20);

        verify(messageStepMapper).summarizeMatchingRuns(eq(7L), eq("FAILED"), eq("%SO_2026%"));
    }

    /** 没筛业务键时那格模式串给 null（SQL 里就不带那个 EXISTS），不是「%%」这种全匹配。 */
    @Test
    void runsSummaryCountsWithoutBusinessKeyPassNullPattern() {
        when(jobRunMapper.selectCount(any())).thenReturn(0L);
        stubPage(List.of());
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(0, 0, 0));

        service.runsSummary(null, null, null, 1, 20);

        // 没筛业务键时那格模式串给 null（SQL 里就不带那个 EXISTS），不是「%%」这种全匹配
        verify(messageStepMapper).summarizeMatchingRuns(isNull(), isNull(), isNull());
    }

    /**
     * 一条消息步都没有的运行照旧回一行 0（不是「查不到」，界面要写「0 消息 · 0 步」）。
     */
    @Test
    void runsSummaryFillsZeroesForRunsWithoutSteps() {
        JobRun empty = runOf(5L, "SUCCESS");
        when(jobRunMapper.selectCount(any())).thenReturn(1L);
        stubPage(List.of(empty));
        when(messageStepMapper.summarizeByRunIds(List.of(5L))).thenReturn(List.of());
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(0, 0, 0));

        Map<String, Object> result = service.runsSummary(null, null, null, 1, 20);

        assertThat(result.get("messages")).isEqualTo(0L);
        assertThat(asMap(((List<?>) result.get("summaries")).get(0)))
                .containsEntry("messageCount", 0L)
                .containsEntry("stepCount", 0L)
                .containsEntry("failedSteps", 0L)
                .containsEntry("matchedBusinessKeys", List.of());
    }

    /** 筛了业务键时只回**命中**的那几个（#129 的「只筛不显示」缺口在这一格补上）。 */
    @Test
    void runsSummaryReturnsOnlyMatchedBusinessKeys() {
        JobRun run = runOf(3L, "SUCCESS");
        when(jobRunMapper.selectCount(any())).thenReturn(1L);
        stubPage(List.of(run));
        when(messageStepMapper.summarizeByRunIds(List.of(3L))).thenReturn(List.of(summary(3L, 1, 0, 1)));
        when(messageStepMapper.selectList(any())).thenReturn(List.of(
                stepOf(3L, "SO-2026-0917-0042"), stepOf(3L, "PO-2026-0001")));
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(1, 0, 1));

        Map<String, Object> result = service.runsSummary(null, null, "SO-2026", 1, 20);

        assertThat(asMap(((List<?>) result.get("summaries")).get(0)).get("matchedBusinessKeys"))
                .isEqualTo(List.of("SO-2026-0917-0042"));
    }

    /**
     * 没筛业务键时不查也不需要那一格：每条运行的全部业务键塞回来，一页二十行就是几十个标签，
     * 而界面上那一块只在「业务键筛出结果」时才有话要说。
     */
    @Test
    void runsSummarySkipsBusinessKeyLookupWhenNotFiltering() {
        JobRun run = runOf(3L, "SUCCESS");
        when(jobRunMapper.selectCount(any())).thenReturn(1L);
        stubPage(List.of(run));
        when(messageStepMapper.summarizeByRunIds(any())).thenReturn(List.of(summary(3L, 1, 0, 1)));
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(1, 0, 1));

        Map<String, Object> result = service.runsSummary(null, null, null, 1, 20);

        assertThat(asMap(((List<?>) result.get("summaries")).get(0)).get("matchedBusinessKeys"))
                .isEqualTo(List.of());
        verify(messageStepMapper, never()).selectList(any());
    }

    /** 命中 0 条运行时不再往下查（不白跑每页摘要那一次消息步查询，也不给它凑一行假摘要）。 */
    @Test
    void runsSummaryStopsWhenNothingMatches() {
        when(jobRunMapper.selectCount(any())).thenReturn(0L);
        stubPage(List.of());
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(0, 0, 0));

        Map<String, Object> result = service.runsSummary(7L, "FAILED", "SO-404", 1, 20);

        assertThat(result.get("runs")).isEqualTo(0L);
        assertThat((List<?>) result.get("summaries")).isEmpty();
        verify(messageStepMapper, never()).summarizeByRunIds(any());
        verify(messageStepMapper, never()).selectList(any());
    }

    /**
     * #130 口径：业务键筛到一条都没有时，**四个数一起归零**。
     *
     * <p>这条是实测抓到的：三个步数曾经只用任务 / 状态算，于是界面写着「运行 0 / 消息 5478」——
     * 一个筛选集里运行是 0 而消息不是 0，自己打自己的脸。</p>
     */
    @Test
    void runsSummaryZeroesEverythingWhenBusinessKeyMatchesNothing() {
        when(jobRunMapper.selectCount(any())).thenReturn(0L);
        stubPage(List.of());
        when(messageStepMapper.summarizeMatchingRuns(any(), any(), any())).thenReturn(totals(0, 0, 0));

        Map<String, Object> result = service.runsSummary(null, null, "SO-404", 1, 20);

        assertThat(result.get("runs")).isEqualTo(0L);
        assertThat(result.get("messages")).isEqualTo(0L);
        assertThat(result.get("steps")).isEqualTo(0L);
        assertThat(result.get("failedSteps")).isEqualTo(0L);
        assertThat((List<?>) result.get("summaries")).isEmpty();
    }

    private JobRun runOf(Long id, String status) {
        JobRun run = new JobRun();
        run.setId(id);
        run.setJobId(7L);
        run.setStatus(status);
        return run;
    }

    private void stubPage(List<JobRun> records) {
        when(jobRunMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<JobRun> page = inv.getArgument(0);
            page.setRecords(records);
            page.setTotal(records.size());
            return page;
        });
    }

    private RunStepTotals totals(long steps, long failedSteps, long messageCount) {
        RunStepTotals totals = new RunStepTotals();
        totals.setStepCount(steps);
        totals.setFailedSteps(failedSteps);
        totals.setMessageCount(messageCount);
        return totals;
    }

    private RunStepSummary summary(Long runId, long steps, long failedSteps, long messageCount) {
        RunStepSummary summary = new RunStepSummary();
        summary.setRunId(runId);
        summary.setStepCount(steps);
        summary.setFailedSteps(failedSteps);
        summary.setMessageCount(messageCount);
        return summary;
    }

    private MessageStep stepOf(Long runId, String businessKey) {
        MessageStep step = new MessageStep();
        step.setRunId(runId);
        step.setBusinessKey(businessKey);
        return step;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
