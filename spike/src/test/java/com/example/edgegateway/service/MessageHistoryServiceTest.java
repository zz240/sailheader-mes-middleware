package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                Map.of("orderNo", "PO-1", "sheet_no", "S-1"), "PO-1", 1);

        ArgumentCaptor<MessageStep> captor = ArgumentCaptor.forClass(MessageStep.class);
        verify(messageStepMapper).insert(captor.capture());
        MessageStep step = captor.getValue();
        assertThat(step.getRunId()).isEqualTo(3L);
        assertThat(step.getMessageId()).isEqualTo("msg-1");
        assertThat(step.getStep()).isEqualTo(2);
        assertThat(step.getActionName()).isEqualTo("map");
        assertThat(step.getStatus()).isEqualTo("SUCCESS");
        assertThat(step.getBusinessKey()).isEqualTo("PO-1");
    }

    @Test
    void recordFailureUpdatesExistingMessageStep() {
        MessageStep existing = new MessageStep();
        existing.setId(9L);
        existing.setStatus("SUCCESS");
        when(messageStepMapper.selectOne(any())).thenReturn(existing);

        service.recordFailure(3L, "msg-1", 3, "upsert", "boom", Map.of("orderNo", "PO-1"), "PO-1", 1);

        assertThat(existing.getStatus()).isEqualTo("FAILED");
        assertThat(existing.getError()).isEqualTo("boom");
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

    @Test
    void extractBusinessKeyReadsDotPath() {
        assertThat(MessageHistoryService.extractBusinessKey(Map.of("a", Map.of("b", "PO-1")), "a.b"))
                .isEqualTo("PO-1");
        assertThat(MessageHistoryService.extractBusinessKey(Map.of("orderNo", "PO-2"), "orderNo"))
                .isEqualTo("PO-2");
        assertThat(MessageHistoryService.extractBusinessKey(Map.of("orderNo", "PO-3"), null)).isNull();
        assertThat(MessageHistoryService.extractBusinessKey(Map.of("orderNo", "PO-4"), "missing")).isNull();
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

    /** #90 边界：上游快照不是 JSON 对象（数组 / 非 JSON 文本）时跳过该步骤，绝不阻断整次重投。 */
    @Test
    void redeliverSkipsUpstreamSnapshotsThatAreNotJsonObjects() throws Exception {
        stubRedeliver(failedStep(4), List.of(
                upstreamStep(1, "SUCCESS", "[{\"order_no\":\"PO-1\"}]"),
                upstreamStep(2, "SUCCESS", "plain text, not json"),
                upstreamStep(3, "SUCCESS", "{\"order_no\":\"PO-1\"}")));
        ArgumentCaptor<Processor> captor = captureResumeProcessor();

        service.redeliver(1L);

        Exchange ex = new DefaultExchange(camelContext);
        captor.getValue().process(ex);
        assertThat(ex.getProperty(MessageHistoryService.RESUME_CONTEXT))
                .isEqualTo(Map.of(3, Map.of("order_no", "PO-1")));
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
}
