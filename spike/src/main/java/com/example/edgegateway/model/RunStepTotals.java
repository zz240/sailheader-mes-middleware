package com.example.edgegateway.model;

/**
 * 一整条筛选集的合计（#130 计数条的那三个数）：消息数 / 消息步数 / 失败步数。
 *
 * <p>与 {@link RunStepSummary} 的区别只在**作用域**：那个是「一条运行」，这个是「当前筛选集里所有运行」
 * ——名字里只用时把作用域写出来，免得 `steps` 一个词在两处指两个范围。</p>
 *
 * <p>由 {@link com.example.edgegateway.mapper.MessageStepMapper#summarizeMatchingRuns} 一条 SQL 算出来。
 * 列名与字段名靠 MyBatis 的下划线转驼峰对上，改名字要两边一起改。</p>
 *
 * <p>没有任何消息步时 SQL 的 `COUNT` / `SUM` 仍回**一行**（0 / NULL / 0），所以这里三个数都是基本类型
 * ——{@code SUM} 那一格由 XML 里的 `ELSE 0` 保证不为 null。</p>
 */
public class RunStepTotals {

    /** 消息步数。 */
    private long stepCount;

    /** 失败步数（status = FAILED）。 */
    private long failedSteps;

    /** 消息数（按「运行 + 消息」去重）。 */
    private long messageCount;

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
