package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 任务：一条可执行的数据同步流程，由动作链（Action）按 index 顺序组成。
 * 支持手动、HTTP、MQ 与自动调度（不自动/间隔/每天/每周）触发；水位策略决定增量同步方式。
 * 自动调度以结构化字段承载（scheduleType 与 interval/schedule 系列字段），领域层用
 * ScheduleConfig 值类型包装（见 getSchedule/setSchedule），内部编译为 fixedRate 或 cron，
 * cron 仅内部表达、不对用户展示/编辑。
 */
@TableName("job")
public class Job {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long siteId;

    private String name;

    private Integer status; // 0 停用，1 启用

    private String crontab;

    /** 自动触发方式：NONE/INTERVAL/DAILY/WEEKLY；NONE 只保留 direct（手动/外部/重投）。 */
    private ScheduleType scheduleType;

    /** 间隔值（INTERVAL）：正整数 N。 */
    private Integer intervalValue;

    /** 间隔单位（INTERVAL）：MINUTES / HOURS。 */
    private String intervalUnit;

    /** 每日/每周执行时间（DAILY/WEEKLY）：HH:MM。 */
    private String scheduleTime;

    /** 每周执行周几（WEEKLY）：1=周一 … 7=周日。 */
    private Integer scheduleWeekday;

    private String timezone;

    private Integer retryCount;

    private Long backoffMs;

    private String remarks;

    /** 业务方向（DS-9）：创建任务时用户显式声明，不再从链入口推断。 */
    private SyncDirection direction;

    /** 最近一次运行结果：SUCCESS / FAILED；每次运行结束由 JobLastRunWriter 统一回写，配置接口不维护。 */
    private String lastRunStatus;

    private LocalDateTime lastRunAt;

    private String lastError;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSiteId() {
        return siteId;
    }

    public void setSiteId(Long siteId) {
        this.siteId = siteId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getCrontab() {
        return crontab;
    }

    public void setCrontab(String crontab) {
        this.crontab = crontab;
    }

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(ScheduleType scheduleType) {
        this.scheduleType = scheduleType;
    }

    public Integer getIntervalValue() {
        return intervalValue;
    }

    public void setIntervalValue(Integer intervalValue) {
        this.intervalValue = intervalValue;
    }

    public String getIntervalUnit() {
        return intervalUnit;
    }

    public void setIntervalUnit(String intervalUnit) {
        this.intervalUnit = intervalUnit;
    }

    public String getScheduleTime() {
        return scheduleTime;
    }

    public void setScheduleTime(String scheduleTime) {
        this.scheduleTime = scheduleTime;
    }

    public Integer getScheduleWeekday() {
        return scheduleWeekday;
    }

    public void setScheduleWeekday(Integer scheduleWeekday) {
        this.scheduleWeekday = scheduleWeekday;
    }

    /** 领域层视角：本任务的调度配置（含时区）。 */
    public ScheduleConfig getSchedule() {
        return ScheduleConfig.from(this);
    }

    /** 用调度配置批量写入扁平字段（含时区）；null 视为 NONE。 */
    public void setSchedule(ScheduleConfig schedule) {
        ScheduleConfig normalized = schedule == null
                ? ScheduleConfig.of(ScheduleType.NONE, null, null, null, null, null)
                : schedule;
        this.scheduleType = normalized.type();
        this.intervalValue = normalized.intervalValue();
        this.intervalUnit = normalized.intervalUnit() == null ? null : normalized.intervalUnit().name();
        this.scheduleTime = normalized.scheduleTime();
        this.scheduleWeekday = normalized.scheduleWeekday();
        this.timezone = normalized.timezone();
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(Long backoffMs) {
        this.backoffMs = backoffMs;
    }

    public String getRemarks() {
        return remarks;
    }

    public SyncDirection getDirection() {
        return direction;
    }

    public void setDirection(SyncDirection direction) {
        this.direction = direction;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getLastRunStatus() {
        return lastRunStatus;
    }

    public void setLastRunStatus(String lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(LocalDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
