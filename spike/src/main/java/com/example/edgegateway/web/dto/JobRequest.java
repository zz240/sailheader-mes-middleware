package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.ScheduleType;

/**
 * 新建/更新任务的请求体；水位策略已移至 READ_SQL 动作（DS-7）。
 * 自动调度为结构化友好配置（scheduleType + 间隔/时间/周几），不再接收裸 cron 表达式。
 * 方向（原 direction）已随 #159 删除：它不参与路由入口、传输、调度与校验。
 */
public record JobRequest(
        String name,
        Long siteId,
        Integer status,
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
