package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.WatermarkType;

/** 任务接口响应体。 */
public record JobView(
        Long id,
        Long siteId,
        String name,
        Integer status,
        String crontab,
        String timezone,
        Integer retryCount,
        Long backoffMs,
        WatermarkType watermarkType,
        String watermarkColumn,
        String remarks
) {

    public static JobView from(Job job) {
        return new JobView(
                job.getId(),
                job.getSiteId(),
                job.getName(),
                job.getStatus(),
                job.getCrontab(),
                job.getTimezone(),
                job.getRetryCount(),
                job.getBackoffMs(),
                job.getWatermarkType(),
                job.getWatermarkColumn(),
                job.getRemarks());
    }
}
