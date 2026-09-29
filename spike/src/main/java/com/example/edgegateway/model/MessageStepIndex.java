package com.example.edgegateway.model;

import java.time.LocalDateTime;

/**
 * 一条运行里某个消息步的**索引行**（#131）：右详情（消息 chips + 步骤链 + 单步卡）画得出界面的那几列，
 * **两个快照不在里面**。
 *
 * <p>为什么不直接回 {@link MessageStep}：`data_snapshot` / `input_snapshot` 是 TEXT，单条上限 64 KB
 * （#127 那条就是它写不进去），而右详情一次要一条运行**全部**的消息步。快照大小由目标端决定、本地拦不住，
 * 整行读回来就是让"点开一条运行"的价格由第三方响应体大小定价。索引行只有几十到两百字节，
 * 一条 720 步的运行（实测库里最大的一条）整条索引也就 100 KB 上下。</p>
 *
 * <p>快照落没落由 {@code hasInputSnapshot} / {@code hasDataSnapshot} 两个布尔回答（步骤链上那两个小点就
 * 是这个信息）；要看快照本身，点开那一步再按 id 单独取整行（#132 的快照面板就是这条路）。</p>
 *
 * <p>写这个类而不是回 Map：列名（{@code run_id / message_id / action_name / has_input_snapshot …}）
 * 与字段名靠 MyBatis 的下划线转驼峰对上，名字对不上时是**空值**而不是报错，所以两边名字必须写死在一起
 * （与 {@link RunStepSummary} 同一个理由）。</p>
 */
public class MessageStepIndex {

    private Long id;

    private Long runId;

    /** 一条消息在本次运行内的稳定标识（UUID）。 */
    private String messageId;

    /** 动作序号（actionIndex）。 */
    private Integer step;

    private String actionName;

    /** SUCCESS / FAILED / REDELIVERED。 */
    private String status;

    /** 失败原文（多行原样保留，界面上直接贴给开发）。 */
    private String error;

    private LocalDateTime createdAt;

    /** 尝试次数（含重试）。 */
    private Integer attempt;

    /**
     * 本步的**动作耗时**（毫秒，#134）。可空：V4 之前落库的消息步没测过，界面写 `—` 而不是 `0 ms`。
     *
     * <p>它必须在索引里（步骤链的每一格、单步卡都写它）：这一列是 BIGINT 的标量，不像两个
     * TEXT 快照列那样按第三方响应体定价，带上它不违反「索引行只有几十到两百字节」那条。</p>
     */
    private Long durationMs;

    /** 业务键（如单号），可为空；界面写「无业务键」。 */
    private String businessKey;

    /**
     * 这一步所属的**批号**（#139）：右详情按它把同一步上的消息归成一批（批号变了的那一步画批徽标）。
     *
     * <p>它在索引里的理由与 {@code durationMs} 一样：一个 VARCHAR(64) 的标量，不像两个 TEXT 快照列
     * 那样按第三方响应体定价，带上它不违反「索引行只有几十到两百字节」那条。</p>
     */
    private String batchId;

    /** 这一步落了输入快照没有（空白算没落——与界面上那两个小点同一个判据）。 */
    private Boolean hasInputSnapshot;

    /** 这一步落了数据快照没有。 */
    private Boolean hasDataSnapshot;

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

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public Boolean getHasInputSnapshot() {
        return hasInputSnapshot;
    }

    public void setHasInputSnapshot(Boolean hasInputSnapshot) {
        this.hasInputSnapshot = hasInputSnapshot;
    }

    public Boolean getHasDataSnapshot() {
        return hasDataSnapshot;
    }

    public void setHasDataSnapshot(Boolean hasDataSnapshot) {
        this.hasDataSnapshot = hasDataSnapshot;
    }
}
