package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.SyncDirection;

import java.time.LocalDateTime;

/** 任务接口响应体（水位策略已移至 READ_SQL 动作，DS-7）。 */
public record JobView(
        Long id,
        Long siteId,
        String name,
        Integer status,
        SyncDirection direction,
        String crontab,
        String timezone,
        Integer retryCount,
        Long backoffMs,
        String remarks,
        String lastRunStatus,
        LocalDateTime lastRunAt,
        String lastError
) {

    public static JobView from(Job job) {
        return new JobView(
                job.getId(),
                job.getSiteId(),
                job.getName(),
                job.getStatus(),
                job.getDirection(),
                job.getCrontab(),
                job.getTimezone(),
                job.getRetryCount(),
                job.getBackoffMs(),
                job.getRemarks(),
                job.getLastRunStatus(),
                job.getLastRunAt(),
                job.getLastError());
    }
}
