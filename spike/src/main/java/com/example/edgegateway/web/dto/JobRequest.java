package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.SyncDirection;
import com.example.edgegateway.model.ScheduleType;

/**
 * 新建/更新任务的请求体；水位策略已移至 READ_SQL 动作（DS-7）。
 * 自动调度为结构化友好配置（scheduleType + 间隔/时间/周几），不再接收裸 cron 表达式。
 */
public record JobRequest(
        String name,
        Long siteId,
        Integer status,
        SyncDirection direction,
        ScheduleType scheduleType,
        Integer intervalValue,
        String intervalUnit,
        String scheduleTime,
        Integer scheduleWeekday,
        String timezone,
        Integer retryCount,
        Long backoffMs,
        String remarks
) {
}
