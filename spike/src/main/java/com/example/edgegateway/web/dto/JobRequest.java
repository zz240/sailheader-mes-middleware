package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.WatermarkType;

/** 新建/更新任务的请求体；watermarkType 与 watermarkColumn 成对出现。 */
public record JobRequest(
        String name,
        Long siteId,
        Integer status,
        String crontab,
        String timezone,
        Integer retryCount,
        Long backoffMs,
        WatermarkType watermarkType,
        String watermarkColumn,
        String remarks
) {
}
