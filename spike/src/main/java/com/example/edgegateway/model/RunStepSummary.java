package com.example.edgegateway.model;

/**
 * 一条运行的规模摘要（#130）：在一次筛选集里，这条运行有多少消息 / 多少消息步 / 多少失败步。
 *
 * <p>由 {@link com.example.edgegateway.mapper.MessageStepMapper#summarizeByRunIds} 按 run_id 聚合出来。
 * 写这个类而不是回 Map：列名（{@code run_id / step_count / failed_steps / message_count}）与字段名
 * 靠 MyBatis 的下划线转驼峰对上，名字对不上时是**空值**而不是报错，所以两边名字必须写死在一起。</p>
 *
 * <p>口径与运行列表同一组筛选（任务 / 状态 / 业务键）——界面上的行摘要因此与展开后按 runId
 * 取到的消息步数对得上。</p>
 */
public class RunStepSummary {

    private Long runId;

    /** 消息步数。 */
    private long stepCount;

    /** 失败步数（status = FAILED）。 */
    private long failedSteps;

    /** 消息数（按 message_id 去重）。 */
    private long messageCount;

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public long getStepCount() {
        return stepCount;
    }

    public void setStepCount(long stepCount) {
        this.stepCount = stepCount;
    }

    public long getFailedSteps() {
        return failedSteps;
    }

    public void setFailedSteps(long failedSteps) {
        this.failedSteps = failedSteps;
    }

    public long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(long messageCount) {
        this.messageCount = messageCount;
    }
}
