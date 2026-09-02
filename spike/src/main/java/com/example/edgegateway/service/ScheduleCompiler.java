package com.example.edgegateway.service;

import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleType;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 把任务的友好调度配置（{@link ScheduleType} + 参数）内部编译成 Spring 调度原语：
 * INTERVAL → fixedRate 毫秒；DAILY/WEEKLY → 6 段 Spring cron 表达式（含时区）。
 * cron 只在内部表达、不对外展示/编辑；调用前数据需已通过 {@link JobService} 校验。
 */
@Component
public class ScheduleCompiler {

    public static final long MINUTE_MS = 60_000L;
    public static final long HOUR_MS = 3_600_000L;

    /** 任务是否为周期触发（间隔/每天/每周）。 */
    public boolean recurring(Job job) {
        return job.getScheduleType() != null && job.getScheduleType() != ScheduleType.NONE;
    }

    /** 是否为固定间隔调度。 */
    public boolean interval(Job job) {
        return job.getScheduleType() == ScheduleType.INTERVAL;
    }

    /** INTERVAL → 固定速率毫秒；非 INTERVAL 抛 {@link IllegalStateException}。 */
    public long fixedRateMs(Job job) {
        if (!interval(job)) {
            throw new IllegalStateException("只有 INTERVAL 调度有固定周期: " + job.getScheduleType());
        }
        long value = job.getIntervalValue() == null ? 0L : job.getIntervalValue();
        return switch (job.getIntervalUnit() == null ? "" : job.getIntervalUnit()) {
            case "MINUTES" -> value * MINUTE_MS;
            case "HOURS" -> value * HOUR_MS;
            default -> throw new IllegalStateException("不支持的定时单位: " + job.getIntervalUnit());
        };
    }

    /** DAILY/WEEKLY → 6 段 Spring cron 表达式；其余返回 {@code null}。 */
    public String cron(Job job) {
        if (job.getScheduleType() == null) {
            return null;
        }
        return switch (job.getScheduleType()) {
            case DAILY -> dailyCron(job.getScheduleTime());
            case WEEKLY -> weeklyCron(job.getScheduleWeekday(), job.getScheduleTime());
            default -> null;
        };
    }

    /** 任务时区：未配置回退系统默认时区。 */
    public ZoneId zone(Job job) {
        String tz = job.getTimezone();
        return tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
    }

    private String dailyCron(String time) {
        int[] hm = hourMinute(time);
        return String.format("0 %d %d * * *", hm[1], hm[0]);
    }

    private String weeklyCron(Integer weekday, String time) {
        int[] hm = hourMinute(time);
        return String.format("0 %d %d * * %d", hm[1], hm[0], weekday);
    }

    /** 解析 HH:MM，返回 {hour, minute}；非法抛 {@link IllegalArgumentException}（可由调用方校验前置拦截）。 */
    private int[] hourMinute(String time) {
        if (time == null || time.length() != 5 || time.charAt(2) != ':') {
            throw new IllegalArgumentException("执行时间必须为 HH:MM: " + time);
        }
        int hour = Integer.parseInt(time.substring(0, 2));
        int minute = Integer.parseInt(time.substring(3, 5));
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("执行时间合法范围 00:00-23:59: " + time);
        }
        return new int[]{hour, minute};
    }
}
