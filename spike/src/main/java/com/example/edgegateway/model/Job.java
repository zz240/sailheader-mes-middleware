package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("job")
public class Job {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long siteId;

    private String name;

    private Integer status;

    private String crontab;

    private String timezone;

    private Integer retryCount;

    private Long backoffMs;

    private WatermarkType watermarkType;

    private String watermarkColumn;

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
