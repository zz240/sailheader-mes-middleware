package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.support.H2MapperTest;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 手动/外部执行（#88）：最近运行结果由统一回写点（{@code finishRun}）写入，执行服务只回读任务返回；
 * 仅当触发层失败、未创建运行时兜底写 FAILED + 根因（真库：内嵌 H2 + 测试 schema.sql）。
 */
@H2MapperTest
class JobExecutionServiceTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private JobMapper jobMapper;

    private JobTrigger jobTrigger;
    private JobLastRunWriter jobLastRunWriter;
    private JobExecutionService service;

    @BeforeEach
    void setUp() {
        jobTrigger = mock(JobTrigger.class);
        jobLastRunWriter = new JobLastRunWriter(jobMapper);
        service = new JobExecutionService(jobMapper, jobTrigger, jobLastRunWriter);
    }

    @Test
    void executeReturnsJobWithResultWrittenByUnifiedWriteback() throws Exception {
        Job job = insertJob();
        LocalDateTime recordedAt = LocalDateTime.of(2026, 9, 8, 10, 30, 0);
        when(jobTrigger.trigger(anyLong(), any())).thenAnswer(inv -> {
            // 模拟链路尾部 finishRun 的统一回写
            jobLastRunWriter.write(job.getId(), "SUCCESS", null, recordedAt);
            return null;
        });

        Job result = service.execute(job.getId(), Map.of("orderNo", "PO-1"));

        // 返回的是回读结果（含统一回写写下的时间），而不是 execute 自己算的当前时间
        assertThat(result.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(result.getLastError()).isNull();
        assertThat(result.getLastRunAt()).isEqualTo(recordedAt);
    }

    @Test
    void executeDoesNotOverwriteFailureAlreadyRecordedByRun() throws Exception {
        Job job = insertJob();
        when(jobTrigger.trigger(anyLong(), any())).thenAnswer(inv -> {
            // 运行内失败：finishRun 已把 job_run.error 写回任务，随后异常抛回调用方
            jobLastRunWriter.write(job.getId(), "FAILED", "run error", LocalDateTime.now());
            throw camelFailure("boom", 42L);
        });

        Job result = service.execute(job.getId(), Map.of());

        assertThat(result.getLastRunStatus()).isEqualTo("FAILED");
        assertThat(result.getLastError()).isEqualTo("run error");
    }

    @Test
    void executeFallsBackToFailureWriteWhenTriggerFailsBeforeRun() throws Exception {
        Job job = insertJob();
        doThrow(new RuntimeException("no consumers available")).when(jobTrigger).trigger(anyLong(), any());

        Job result = service.execute(job.getId(), Map.of());

        assertThat(result.getLastRunStatus()).isEqualTo("FAILED");
        assertThat(result.getLastError()).isEqualTo("no consumers available");
        assertThat(jobMapper.selectById(job.getId()).getLastRunStatus()).isEqualTo("FAILED");
    }

    @Test
    void rejectsDisabledJob() {
        Job job = insertJob();
        job.setStatus(0);
        jobMapper.updateById(job);

        assertThatThrownBy(() -> service.execute(job.getId(), Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void rejectsMissingJob() {
        assertThatThrownBy(() -> service.execute(999_999L, Map.of()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("任务不存在");
    }

    /** 构造与真实链路一致的失败异常：Camel 包装、exchange 上带本次运行的 runId。 */
    private CamelExecutionException camelFailure(String rootMessage, Long runId) {
        DefaultExchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(MessageHistoryService.RUN_ID, runId);
        return new CamelExecutionException("Exception occurred during execution", exchange,
                new RuntimeException(rootMessage));
    }

    private Job insertJob() {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName("job-exec-" + SEQ.incrementAndGet());
        job.setStatus(1);
        jobMapper.insert(job);
        return job;
    }
}
