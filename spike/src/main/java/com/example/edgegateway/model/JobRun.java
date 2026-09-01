package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 运行（Job Run）：一次任务被触发并执行的全过程（手动、定时、外部触发、重投各算一次）。
 * 记录该次执行的起止时间与最终状态（RUNNING/SUCCESS/FAILED）以及原始输入快照，供消息历史按运行检索。
 */
@TableName("job_run")
public class JobRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    /** RUNNING / SUCCESS / FAILED。 */
    private String status;

    /** 该次执行的原始输入快照（JSON 字符串，可为空）。 */
    private String inputSnapshot;

    private String error;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInputSnapshot() {
        return inputSnapshot;
    }

    public void setInputSnapshot(String inputSnapshot) {
        this.inputSnapshot = inputSnapshot;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
