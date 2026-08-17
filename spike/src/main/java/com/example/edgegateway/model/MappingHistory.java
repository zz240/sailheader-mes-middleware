package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 映射历史（资产沉淀）：按 站点 + 方向 + 源结构指纹 隔离的已确认字段映射。
 * hit_count 为该映射被复用/再次确认的次数，updated_at 为最近确认时间。
 */
@TableName(value = "mapping_history", autoResultMap = true)
public class MappingHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long siteId;

    private SyncDirection direction;

    private String sourceKey;

    private String sourcePath;

    private String targetName;

    private String transform;

    private String datePattern;

    private String constant;

    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> enumMap;

    private Integer hitCount;

    private LocalDateTime updatedAt;

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

    public SyncDirection getDirection() {
        return direction;
    }

    public void setDirection(SyncDirection direction) {
        this.direction = direction;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getTransform() {
        return transform;
    }

    public void setTransform(String transform) {
        this.transform = transform;
    }

    public String getDatePattern() {
        return datePattern;
    }

    public void setDatePattern(String datePattern) {
        this.datePattern = datePattern;
    }

    public String getConstant() {
        return constant;
    }

    public void setConstant(String constant) {
        this.constant = constant;
    }

    public Map<String, Object> getEnumMap() {
        return enumMap;
    }

    public void setEnumMap(Map<String, Object> enumMap) {
        this.enumMap = enumMap;
    }

    public Integer getHitCount() {
        return hitCount;
    }

    public void setHitCount(Integer hitCount) {
        this.hitCount = hitCount;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
