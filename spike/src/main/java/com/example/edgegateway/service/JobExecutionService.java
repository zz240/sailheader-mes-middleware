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
 * 手动/外部执行：把任务输入经 {@link JobTrigger} 送入生成的路由并同步等待执行结果，
 * 再回读任务返回（最近运行结果由链路尾部 {@code finishRun} 统一回写，见 {@link JobLastRunWriter}）。
 */
@Service
public class JobExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(JobExecutionService.class);

    private final JobMapper jobMapper;
    private final JobTrigger jobTrigger;
    private final JobLastRunWriter jobLastRunWriter;

    public JobExecutionService(JobMapper jobMapper, JobTrigger jobTrigger, JobLastRunWriter jobLastRunWriter) {
        this.jobMapper = jobMapper;
        this.jobTrigger = jobTrigger;
        this.jobLastRunWriter = jobLastRunWriter;
    }

    /**
     * 手动/外部执行：把任务输入经 {@link JobTrigger} 送入生成的路由，同步等待执行结果。
     * 最近运行结果由统一回写点（{@code MessageHistoryService.finishRun}）写入任务，这里回读任务返回；
     * 只有触发层失败（未创建运行）时才兜底回写 FAILED + 根因，避免任务列表与真实情况不一致。
     */
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
        } catch (Exception e) {
            if (JobTrigger.runIdOf(e) == null) {
                jobLastRunWriter.write(jobId, "FAILED", rootMessage(e), LocalDateTime.now());
            }
            LOG.error("任务执行失败 job={}", jobId, e);
        }
        Job updated = jobMapper.selectById(jobId);
        return updated == null ? job : updated;
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
