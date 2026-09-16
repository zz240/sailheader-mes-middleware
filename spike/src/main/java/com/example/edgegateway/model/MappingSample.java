package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 编辑器里粘进来的那份样例的原文（#119）。一格 = 任务 + 用途 + 归属，见 {@link MappingSampleKind}。
 *
 * <p>存的是**原文**，不是推出来的字段清单：清单是原文的纯函数，探测那条路与粘贴那条路共用同一份展开，
 * 每次现推就够；原文还能给试算（#116）用。站点内共享（不区分谁放的），不做版本、不做按人隔离。</p>
 */
@TableName("mapping_sample")
public class MappingSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private MappingSampleKind kind;

    /** {@code SOURCE} = 动作序号（0 = 任务输入）；{@code TARGET} = 写目标动作 id。 */
    private Long refId;

    /** 粘进来的那段文本，原样存。 */
    private String sampleJson;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public MappingSampleKind getKind() {
        return kind;
    }

    public void setKind(MappingSampleKind kind) {
        this.kind = kind;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }

    public String getSampleJson() {
        return sampleJson;
    }

    public void setSampleJson(String sampleJson) {
        this.sampleJson = sampleJson;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
