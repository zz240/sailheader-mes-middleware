package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 任务：一条可执行的数据同步流程，由动作链（Action）按 index 顺序组成。
 * 支持 cron 定时、手动、HTTP、MQ 四种触发；水位策略决定增量同步方式。
 */
@TableName("job")
public class Job {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long siteId;

    private String name;

    private Integer status; // 0 停用，1 启用

    private String crontab;

    private String timezone;

    private Integer retryCount;

    private Long backoffMs;

    private WatermarkType watermarkType; // 水位策略：TIMESTAMP（时间列）或 SEQUENCE（自增列）

    private String watermarkColumn; // 对应的增量列名，路由生成器据此生成 where 条件

    private String remarks;

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

    public WatermarkType getWatermarkType() {
        return watermarkType;
    }

    public void setWatermarkType(WatermarkType watermarkType) {
        this.watermarkType = watermarkType;
    }

    public String getWatermarkColumn() {
        return watermarkColumn;
    }

    public void setWatermarkColumn(String watermarkColumn) {
        this.watermarkColumn = watermarkColumn;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
}
