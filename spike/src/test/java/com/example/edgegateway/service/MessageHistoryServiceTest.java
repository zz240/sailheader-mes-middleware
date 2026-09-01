package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
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
    }

    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final CamelContext camelContext = mock(CamelContext.class);
    private final MessageHistoryService service = new MessageHistoryService(
            jobRunMapper, messageStepMapper, jobMapper, new ObjectMapper(), camelContext);

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
