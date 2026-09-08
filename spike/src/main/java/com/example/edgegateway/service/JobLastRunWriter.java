package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 任务"最近一次运行结果"的唯一写入点（#88）：把一次运行的结果回写到
 * {@code job.last_run_status/last_run_at/last_error}，覆盖 MQ 消费、自动调度、手动执行与断点重投全部链路。
 */
@Component
public class JobLastRunWriter {

    private final JobMapper jobMapper;

    public JobLastRunWriter(JobMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    /** 把某次运行的结果写回任务；jobId 为空或任务已删除时静默跳过。 */
    public void write(Long jobId, String status, String error, LocalDateTime finishedAt) {
        if (jobId == null) {
            return;
        }
        // 用 update 包装器显式 SET：成功（error=null）必须把上一次的 last_error 清空，
        // 而 updateById 的 null 字段会被默认 NOT_NULL 策略跳过、留下过期错误。
        jobMapper.update(null, new LambdaUpdateWrapper<Job>()
                .eq(Job::getId, jobId)
                .set(Job::getLastRunStatus, status)
                .set(Job::getLastRunAt, finishedAt)
                .set(Job::getLastError, error));
    }
}
