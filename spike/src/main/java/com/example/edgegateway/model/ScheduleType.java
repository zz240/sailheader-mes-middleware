package com.example.edgegateway.model;

/**
 * 任务自动触发方式（友好配置，非裸 cron 表达式）：
 * NONE = 不自动（只保留 direct：手动/外部/重投）；INTERVAL = 每 N 分钟/小时；
 * DAILY = 每天 HH:MM；WEEKLY = 每周（周几）+ HH:MM。
 */
public enum ScheduleType {
    NONE,
    INTERVAL,
    DAILY,
    WEEKLY
}
