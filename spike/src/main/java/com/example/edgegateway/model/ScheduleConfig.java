package com.example.edgegateway.model;

import java.time.ZoneId;

/**
 * 任务自动触发的结构化调度（友好配置）：类型 + 间隔/时间/周几 + 时区。
 * 本值类型是深 module：解析、校验、编译（fixedRate / cron+zone）都封在背后，
 * 对外只暴露 {@code of}/{@code from} 工厂与 {@link #plan()} 一个编译入口。
 * 持久化仍以 Job 的扁平列承载，本类型在领域层使用（不直接映射数据库）。
 */
public record ScheduleConfig(
        ScheduleType type,
        Integer intervalValue,
        IntervalUnit intervalUnit,
        String scheduleTime,
        Integer scheduleWeekday,
        String timezone) {

    public static final long MINUTE_MS = 60_000L;
    public static final long HOUR_MS = 3_600_000L;

    /** 从友好表单字段解析并校验；非法输入抛 {@link IllegalArgumentException}（信息可定位）。 */
    public static ScheduleConfig of(ScheduleType type,
                                    Integer intervalValue,
                                    String intervalUnit,
                                    String scheduleTime,
                                    Integer scheduleWeekday,
                                    String timezone) {
        ScheduleType resolved = type == null ? ScheduleType.NONE : type;
        ScheduleConfig config = new ScheduleConfig(
                resolved, intervalValue, parseUnit(intervalUnit), scheduleTime, scheduleWeekday, timezone);
        config.validate();
        return config;
    }

    /** 从 Job 的扁平字段还原（供路由/调度运行时使用，不校验——保存时已校验）。 */
    public static ScheduleConfig from(Job job) {
        if (job == null) {
            return new ScheduleConfig(ScheduleType.NONE, null, null, null, null, null);
        }
        return new ScheduleConfig(
                job.getScheduleType() == null ? ScheduleType.NONE : job.getScheduleType(),
                job.getIntervalValue(),
                parseUnit(job.getIntervalUnit()),
                job.getScheduleTime(),
                job.getScheduleWeekday(),
                job.getTimezone());
    }

    private static IntervalUnit parseUnit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return IntervalUnit.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void validate() {
        switch (type) {
            case NONE -> {
                // 不自动：无需其它字段
            }
            case INTERVAL -> {
                if (intervalValue == null) {
                    throw new IllegalArgumentException("定时间隔值不能为空");
                }
                if (intervalValue <= 0) {
                    throw new IllegalArgumentException("定时间隔必须为正整数");
                }
                if (intervalUnit == null) {
                    throw new IllegalArgumentException("定时单位只能是 MINUTES 或 HOURS");
                }
            }
            case DAILY -> validateTime(scheduleTime, "每日");
            case WEEKLY -> {
                if (scheduleWeekday == null || scheduleWeekday < 1 || scheduleWeekday > 7) {
                    throw new IllegalArgumentException("请选择周几（1=周一 … 7=周日）");
                }
                validateTime(scheduleTime, "每周");
            }
            default -> throw new IllegalArgumentException("不支持的调度方式: " + type);
        }
        validateZone();
    }

    private void validateTime(String time, String label) {
        if (time == null || time.isBlank()) {
            throw new IllegalArgumentException(label + "执行时间不能为空");
        }
        if (time.length() != 5 || time.charAt(2) != ':') {
            throw new IllegalArgumentException(label + "执行时间必须是 HH:MM");
        }
        try {
            int hour = Integer.parseInt(time.substring(0, 2));
            int minute = Integer.parseInt(time.substring(3, 5));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "执行时间合法范围 00:00-23:59: " + time);
        }
    }

    private void validateZone() {
        if (timezone != null && !timezone.isBlank()) {
            try {
                ZoneId.of(timezone);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("时区非法: " + timezone);
            }
        }
    }

    public boolean recurring() {
        return type != ScheduleType.NONE;
    }

    public boolean interval() {
        return type == ScheduleType.INTERVAL;
    }

    /** 任务时区：未配置回退系统默认。 */
    public ZoneId zone() {
        return timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone);
    }

    /** 系统内部 cron（DAILY/WEEKLY），供 Job.crontab 持久化；其余返回 {@code null}。 */
    public String internalCron() {
        return switch (type) {
            case DAILY -> dailyCron();
            case WEEKLY -> weeklyCron();
            default -> null;
        };
    }

    /** INTERVAL → fixedRate 毫秒；非 INTERVAL 抛 {@link IllegalStateException}。 */
    public long fixedRateMs() {
        if (!interval()) {
            throw new IllegalStateException("只有 INTERVAL 调度有固定周期: " + type);
        }
        long value = intervalValue == null ? 0L : intervalValue;
        return intervalUnit() == IntervalUnit.HOURS ? value * HOUR_MS : value * MINUTE_MS;
    }

    /** 触发计划：INTERVAL → fixedRate；DAILY/WEEKLY → cron+zone；NONE 抛（仅周期调度调用）。 */
    public Plan plan() {
        if (type == ScheduleType.NONE) {
            throw new IllegalStateException("无调度计划: " + type);
        }
        if (interval()) {
            return new Plan(type, fixedRateMs(), null, zone());
        }
        return new Plan(type, null, internalCron(), zone());
    }

    private String dailyCron() {
        int[] hm = hourMinute();
        return String.format("0 %d %d * * *", hm[1], hm[0]);
    }

    private String weeklyCron() {
        int[] hm = hourMinute();
        return String.format("0 %d %d * * %d", hm[1], hm[0], scheduleWeekday);
    }

    private int[] hourMinute() {
        int hour = Integer.parseInt(scheduleTime.substring(0, 2));
        int minute = Integer.parseInt(scheduleTime.substring(3, 5));
        return new int[]{hour, minute};
    }

    /** 编译后的触发计划（框架无关；由调度 runtime 适配为 Spring 任务）。 */
    public record Plan(ScheduleType type, Long fixedRateMs, String cron, ZoneId zone) {
        public boolean isFixedRate() {
            return type == ScheduleType.INTERVAL;
        }

        public boolean isCron() {
            return !isFixedRate();
        }
    }
}
