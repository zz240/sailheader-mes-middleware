package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.support.H2MapperTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务"最近一次运行结果"写入点（#88）：真库（内嵌 H2 + 测试 schema.sql）验证
 * 回写状态/时间/错误，以及成功时把上一次的 last_error 清空。
 */
@H2MapperTest
class JobLastRunWriterTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private JobMapper jobMapper;

    private JobLastRunWriter writer;

    @BeforeEach
    void setUp() {
        writer = new JobLastRunWriter(jobMapper);
    }

    @Test
    void writeFailureRecordsStatusTimeAndError() {
        Job job = insertJob();
        LocalDateTime finishedAt = LocalDateTime.of(2026, 9, 8, 10, 30, 0);

        writer.write(job.getId(), "FAILED", "connection refused", finishedAt);

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("FAILED");
        assertThat(updated.getLastError()).isEqualTo("connection refused");
        assertThat(updated.getLastRunAt()).isEqualTo(finishedAt);
    }

    @Test
    void writeSuccessClearsPreviousError() {
        Job job = insertJob();
        writer.write(job.getId(), "FAILED", "boom", LocalDateTime.of(2026, 9, 8, 10, 0, 0));

        writer.write(job.getId(), "SUCCESS", null, LocalDateTime.of(2026, 9, 8, 11, 0, 0));

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastError()).isNull();
        assertThat(updated.getLastRunAt()).isEqualTo(LocalDateTime.of(2026, 9, 8, 11, 0, 0));
    }

    @Test
    void writeIgnoresUnknownJob() {
        writer.write(999_999L, "FAILED", "boom", LocalDateTime.now());
        // 任务被删除/不存在时静默跳过（更新 0 行），不抛异常
        assertThat(jobMapper.selectById(999_999L)).isNull();
    }

    private Job insertJob() {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName("job-last-run-" + SEQ.incrementAndGet());
        job.setStatus(1);
        jobMapper.insert(job);
        return job;
    }
}
