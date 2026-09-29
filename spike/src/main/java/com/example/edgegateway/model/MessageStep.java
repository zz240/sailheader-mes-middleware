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

    /**
     * 本步的**动作耗时**（毫秒，#134）：这个动作从开始执行到返回的墙钟时长，用来回答「哪一步慢」。
     *
     * <p>可空，而且是**真的可空**：这一列之前落库的消息步没测过（V4 迁移不回溯），界面那一格
     * 写 `—` 而不是 `0 ms`——「没测」与「测得就是 0 ms」是两件事（ADR-0016 决策 7）。</p>
     *
     * <p>失败步也记：耗时取自**最后一次尝试**的动作执行（不含重试退避那段等待，
     * 所以它不是步间隔）。</p>
     */
    private Long durationMs;

    /** 业务键（如单号），按源动作 businessKey 在消息创建时提取，可为空。 */
    private String businessKey;

    /**
     * 这一步所属的**批号**（#139 / ADR-0017）：某一个动作上、被同一次基数变化动作归到一起的那组消息
     * 的编号。每执行一次现生成，随消息在链上**继承到下一个基数变化动作为止**——同一批的每一步都带
     * 同一个号，所以「哪几条是同一批」在库里答得出来（此前它只活在运行期的交换器属性里）。
     *
     * <p>可空，而且是**真的可空**：MQ 消费 / 任务输入这些链根本没有读库动作；这一列之前落库的老数据
     * 也没有它（V5 迁移不回溯）。空 = 「这一步没有批次」，不是「这条自己一批」。</p>
     */
    private String batchId;

    /**
     * **批内序号**（#139）：拆分的元素顺序 / 合并的攒批顺序，从 {@code 1} 起。
     *
     * <p>只在拆分与合并动作写；读库产生的那些行**留空**——读取批里「第几条」没有额外语义。</p>
     */
    private Integer batchSeq;

    /**
     * **父消息标识**（#139）：这条消息是从哪条消息拆出来的（拆分动作的输入就是父消息）。
     *
     * <p>它管**谱系**、批号管**分组**，两件事分开：一条父消息被拆两次时，两次的子消息这一格完全相同，
     * 只有批号分得开（ADR-0017 决策 4）。</p>
     */
    private String parentMessageId;

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

    public Integer getBatchSeq() {
        return batchSeq;
    }

    public void setBatchSeq(Integer batchSeq) {
        this.batchSeq = batchSeq;
    }

    public String getParentMessageId() {
        return parentMessageId;
    }

    public void setParentMessageId(String parentMessageId) {
        this.parentMessageId = parentMessageId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    /**
     * 把一份**批次戳**落到这一行的三格上（#139）：批号 / 批内序号 / 父消息标识是一组，一起写。
     *
     * <p>{@code null} 与 {@link BatchStamp#NONE} 一个意思：这三格都空（MQ 消费 / 任务输入那些链，
     * 以及读取批没有批内序号的那些行）。兜底放在这一处，写库那条路就不必自己判一遍。</p>
     */
    public void applyBatch(BatchStamp batch) {
        BatchStamp stamp = batch == null ? BatchStamp.NONE : batch;
        this.batchId = stamp.batchId();
        this.batchSeq = stamp.batchSeq();
        this.parentMessageId = stamp.parentMessageId();
    }
}
