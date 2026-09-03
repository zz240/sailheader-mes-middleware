package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 手动执行：把任务输入经 {@link JobTrigger} 送入生成的路由，
 * 同步等待执行结果并把最近一次状态/时间/错误写回任务。
 */
@Service
public class JobExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutionService.class);

    private final JobMapper jobMapper;
    private final JobTrigger jobTrigger;

    public JobExecutionService(JobMapper jobMapper, JobTrigger jobTrigger) {
        this.jobMapper = jobMapper;
        this.jobTrigger = jobTrigger;
    }

    public Job execute(Long jobId, Map<String, Object> input) {
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new NoSuchElementException("任务不存在: " + jobId);
        }
        if (job.getStatus() == null || job.getStatus() != 1) {
            throw BusinessException.of(BusinessCode.JOB_NOT_ENABLED, "任务未启用，无法执行");
        }
        try {
            jobTrigger.trigger(jobId, input);
            job.setLastRunStatus("SUCCESS");
            job.setLastError(null);
        } catch (Exception e) {
            job.setLastRunStatus("FAILED");
            job.setLastError(rootMessage(e));
            LOG.error("任务执行失败 job={}", jobId, e);
        }
        job.setLastRunAt(LocalDateTime.now());
        jobMapper.updateById(job);
        return job;
    }

    /** 取异常链最深层原因的消息（跳过 Camel 的包装信息，便于定位真实错误）。 */
    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? throwable.getMessage() : message;
    }
}
