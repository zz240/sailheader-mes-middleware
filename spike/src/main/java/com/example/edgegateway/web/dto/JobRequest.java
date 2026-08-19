package com.example.edgegateway.web.dto;

/** 新建/更新任务的请求体；水位策略已移至 READ_SQL 动作（DS-7）。 */
public record JobRequest(
        String name,
        Long siteId,
        Integer status,
        String crontab,
        String timezone,
        Integer retryCount,
        Long backoffMs,
        String remarks
) {
}
