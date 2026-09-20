package com.example.edgegateway.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 消息步（Message Step）：一条消息在执行时穿过单个动作的一次结果。
 * 是消息历史的最小单位（含该步状态/数据/错误），按 (run_id, message_id, step) 唯一。
 * status ∈ SUCCESS/FAILED/REDELIVERED（RETRYING/SUPPRESSED 预留）。
 */
@TableName("message_step")
public class MessageStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** 一条消息在本次运行内的稳定标识（UUID）。 */
    private String messageId;

    /** 动作序号（actionIndex）。 */
    private Integer step;

    private String actionName;

    /** SUCCESS / FAILED / REDELIVERED。 */
    private String status;

    /** 该步执行后的数据/上下文快照（JSON 字符串）。 */
    private String dataSnapshot;

    /** 进入该步的原始输入快照（JSON 字符串）。 */
    private String inputSnapshot;

    private String error;

    private LocalDateTime createdAt;

    /** 尝试次数（含重试）。 */
    private Integer attempt;

    /** 业务键（如单号），按源动作 businessKey 在消息创建时提取，可为空。 */
    private String businessKey;

    /**
     * 所属运行的 {@code jobId}（#136，**只读投影，不是表上的列**）：消息步检索模式每行要写「任务 #id」，
     * 而这一步自己只带 {@code runId}。由
     * {@link com.example.edgegateway.service.MessageHistoryService#listSteps} 按页补齐
     * （一次 {@code in} 查询，不在前端按行补拉运行）；查不到运行（脏数据）时留空，界面写「任务 —」。
     */
    @TableField(exist = false)
    private Long jobId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDataSnapshot() {
        return dataSnapshot;
    }

    public void setDataSnapshot(String dataSnapshot) {
        this.dataSnapshot = dataSnapshot;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }
}
