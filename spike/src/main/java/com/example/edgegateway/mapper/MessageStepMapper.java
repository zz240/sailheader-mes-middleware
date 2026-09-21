package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.model.MessageStepIndex;
import com.example.edgegateway.model.RunStepSummary;
import com.example.edgegateway.model.RunStepTotals;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageStepMapper extends BaseMapper<MessageStep> {

    /**
     * 一条运行的**消息步索引**，按页取（#131）：右详情要的那几列，**不含两个 TEXT 快照列**
     * （理由见 {@link MessageStepIndex}）。
     *
     * <p>顺序按 id 升序——这是一条运行的**步骤顺序**；运行列表与消息步检索那边的「最新在上」
     * （{@code orderByDesc(id)}）是检索面的口径，不是这里。</p>
     *
     * <p>落在 XML 里（`mapper/MessageStepMapper.xml`），与这个 mapper 的另外两条自定义查询同一个家：
     * 同一个实体的自定义 SQL 分两处放，读的人会以为口径不一样。</p>
     */
    Page<MessageStepIndex> pageRunStepIndex(Page<MessageStepIndex> page, @Param("runId") Long runId);

    /**
     * 把这一批运行的消息数 / 消息步数 / 失败步数聚合出来（#130），一次查询，不是每行一次。
     *
     * <p>为什么不能每行一次：运行列表要写 `N 消息 · M 步 · 失败 K`，而 `message_step` 上没有 jobId，
     * 逐行补拉就是 ADR-0016 决策 6 拒绝的 N+1。传进来的 runIds 只是**当前这一页**的运行——
     * 整条筛选集的合计走 {@link #summarizeMatchingRuns}（判据下推，不把 id 拉回内存）。</p>
     *
     * <p>走 XML 映射文件（`mapper/MessageStepMapper.xml`）：那段 SQL 里有 `&lt;foreach&gt;`，
     * 注解写不动。列名与 {@link RunStepSummary} 的字段名靠下划线转驼峰对上。</p>
     */
    List<RunStepSummary> summarizeByRunIds(@Param("runIds") List<Long> runIds);

    /**
     * 整条筛选集的合计（#130 计数条的三个数）：与运行列表**同一组筛选**（任务 / 状态 / 业务键），
     * 一条 SQL 出结果——**不把命中运行的 id 拉回内存再拼 `IN`**（整库运行时那个 `IN` 是无界的，
     * 正是 ADR-0016 结果 ⑥ 刚从 `listRuns` 收掉的那个毛病）。
     *
     * <p>{@code businessKeyPattern} 传 `%关键词%` 那种 `LIKE` 模式串；它按 `{0}` 占位绑定，
     * 不是字符串拼接。</p>
     */
    RunStepTotals summarizeMatchingRuns(@Param("jobId") Long jobId,
                                        @Param("status") String status,
                                        @Param("businessKeyPattern") String businessKeyPattern);
}
