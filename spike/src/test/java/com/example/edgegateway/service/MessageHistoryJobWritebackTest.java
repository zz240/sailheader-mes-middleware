package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.support.H2MapperTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 统一回写（#88）：所有链路尾部的 {@code finishRun} 都要把本次运行结果写回任务
 * （真库：内嵌 H2 + 测试 schema.sql），手动执行链路不再单独回写。
 */
@H2MapperTest
class MessageHistoryJobWritebackTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private JobMapper jobMapper;

    @Autowired
    private JobRunMapper jobRunMapper;

    @Autowired
    private MessageStepMapper messageStepMapper;

    @Autowired
    private ActionMapper actionMapper;

    private MessageHistoryService service;

    @BeforeEach
    void setUp() {
        JobLastRunWriter writer = new JobLastRunWriter(jobMapper);
        service = new MessageHistoryService(jobRunMapper, messageStepMapper, jobMapper, actionMapper,
                new ObjectMapper(), mock(CamelContext.class), writer);
    }

    @Test
    void finishRunFailureWritesJobLastRun() {
        Job job = insertJob();
        JobRun run = insertRun(job.getId(), "RUNNING");

        service.finishRun(run.getId(), "FAILED", "connection refused");

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("FAILED");
        assertThat(updated.getLastError()).isEqualTo("connection refused");
        assertThat(updated.getLastRunAt()).isNotNull();
    }

    @Test
    void finishRunSuccessClearsJobLastError() {
        Job job = insertJob();
        JobRun run = insertRun(job.getId(), "RUNNING");
        service.finishRun(run.getId(), "FAILED", "boom");
        JobRun secondRun = insertRun(job.getId(), "RUNNING");

        service.finishRun(secondRun.getId(), "SUCCESS", null);

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    void finishRunOnAlreadyFinishedRunDoesNotRewriteJob() {
        Job job = insertJob();
        LocalDateTime recordedAt = LocalDateTime.of(2026, 9, 8, 9, 0, 0);
        job.setLastRunStatus("SUCCESS");
        job.setLastRunAt(recordedAt);
        jobMapper.updateById(job);
        JobRun finished = insertRun(job.getId(), "FAILED");

        service.finishRun(finished.getId(), "SUCCESS", null);

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastRunAt()).isEqualTo(recordedAt);
    }

    private Job insertJob() {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName("job-writeback-" + SEQ.incrementAndGet());
        job.setStatus(1);
        jobMapper.insert(job);
        return job;
    }

    private JobRun insertRun(Long jobId, String status) {
        JobRun run = new JobRun();
        run.setJobId(jobId);
        run.setStatus(status);
        run.setStartedAt(LocalDateTime.now());
        jobRunMapper.insert(run);
        return run;
    }
}
