package com.example.edgegateway.service;

import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.support.H2MapperTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * #130 计数口径的**真 SQL** 测试：把服务接在真实的 mapper 上（H2 建表 + 真 SQL），
 * 钉住「四个计数就是当前筛选集的那四个数」。
 *
 * <p>为什么单开一个类：纯 Mockito 那套（{@link MessageHistoryServiceTest}）只能证明「参数传下去了」，
 * 证明不了那段 SQL 真的算对了——业务键筛选下推成 `EXISTS`、三个数一条 `JOIN` 聚合出来，
 * 这两个都是 SQL 层面的行为。同一个筛选条件在两处（列表 / 计数）写得不一致，只有真库能抓。</p>
 */
@H2MapperTest
class MessageHistoryServiceSummarySqlTest {

    @Autowired
    private JobRunMapper jobRunMapper;

    @Autowired
    private MessageStepMapper messageStepMapper;

    /**
     * 每个用例从空库开始：这个切片是同一个内存库 + {@code schema.sql}（IF NOT EXISTS），
     * 不等它自己清的话，上一个用例插的运行会算进这一个用例的计数里——「翻到第 3 页摘要是空的」
     * 这种断言就会莫名其妙地翻车（实测踩到过）。
     */
    @BeforeEach
    void clearHistory() {
        messageStepMapper.delete(null);
        jobRunMapper.delete(null);
    }

    /** 服务只用到这四个 mapper（其余依赖与计数无关），拿桩顶掉。 */
    private MessageHistoryService newService() {
        JobMapper jobMapper = mock(JobMapper.class);
        return new MessageHistoryService(jobRunMapper, messageStepMapper, jobMapper, mock(ActionMapper.class),
                new ObjectMapper(), mock(CamelContext.class), new JobLastRunWriter(jobMapper));
    }

    /**
     * 计数 = 当前筛选集：任务 / 状态 / 业务键三格都算数，且**不含分页**
     * （第 1 页与第 3 页回的四个数一样，摘要却不一样）。
     */
    @Test
    void countsFollowJobStatusAndBusinessKeyAndIgnorePaging() {
        // 任务 7 的第 1 条：业务键命中（子串命中「SO-2026-0917-0042」），三步里两步失败
        JobRun hit = insertRun(7L, "FAILED");
        insertStep(hit.getId(), "msg-a", 1, "SUCCESS", "SO-2026-0917-0042");
        insertStep(hit.getId(), "msg-a", 2, "FAILED", "SO-2026-0917-0042");
        insertStep(hit.getId(), "msg-b", 3, "FAILED", "SO-2026-0917-0042");
        // 任务 7 的第 2 条：业务键不命中（不该进筛选集）
        JobRun miss = insertRun(7L, "FAILED");
        insertStep(miss.getId(), "msg-c", 1, "SUCCESS", "PO-2026-0001");
        // 任务 9：业务键命中但任务不命中（不该进筛选集）
        JobRun otherJob = insertRun(9L, "FAILED");
        insertStep(otherJob.getId(), "msg-d", 1, "FAILED", "SO-2026-0917-0042");

        MessageHistoryService service = newService();
        Map<String, Object> first = service.runsSummary(7L, "FAILED", "SO-2026-0917-0042", 1, 20);

        assertThat(first.get("runs")).isEqualTo(1L);
        assertThat(first.get("messages")).isEqualTo(2L);
        assertThat(first.get("steps")).isEqualTo(3L);
        assertThat(first.get("failedSteps")).isEqualTo(2L);

        // 同一组筛选、不筛业务键：任务 7 里两条运行都算（说明业务键那一格真的参与了缩窄）
        Map<String, Object> withoutKey = service.runsSummary(7L, "FAILED", null, 1, 20);
        assertThat(withoutKey.get("runs")).isEqualTo(2L);
        assertThat(withoutKey.get("steps")).isEqualTo(4L);

        // 同一组筛选、换个页与换个每页条数：四个计数一个都没变（票面「翻页不影响计数」）。
        // 这里只断言计数，不赌「某一页是空的」——这个切片没有装分页拦截器（那是应用上下文里的
        // MybatisPlusConfig），page 参数在切片的真 SQL 上不生效；真机脚本验的是那一段。
        Map<String, Object> otherPage = service.runsSummary(7L, "FAILED", "SO-2026-0917-0042", 9, 1);
        assertThat(otherPage.get("runs")).isEqualTo(1L);
        assertThat(otherPage.get("messages")).isEqualTo(2L);
        assertThat(otherPage.get("steps")).isEqualTo(3L);
        assertThat(otherPage.get("failedSteps")).isEqualTo(2L);
        assertThat((java.util.List<?>) otherPage.get("summaries")).hasSize(1);
    }

    /** 不筛时整个库的合计：三条运行五步三消息（消息按「运行 + 消息」去重）。 */
    @Test
    void countsWithoutFiltersCoverEveryRun() {
        JobRun first = insertRun(7L, "SUCCESS");
        insertStep(first.getId(), "msg-a", 1, "SUCCESS", "SO-1");
        insertStep(first.getId(), "msg-a", 2, "SUCCESS", "SO-1");
        JobRun second = insertRun(9L, "FAILED");
        insertStep(second.getId(), "msg-a", 1, "FAILED", "SO-1");
        insertStep(second.getId(), "msg-b", 1, "FAILED", "SO-2");
        JobRun noSteps = insertRun(7L, "SUCCESS");

        Map<String, Object> result = newService().runsSummary(null, null, null, 1, 20);

        assertThat(result.get("runs")).isEqualTo(3L);
        // 同一条消息 id 出现在两条运行里算两条（去重键是「运行 + 消息」）
        assertThat(result.get("messages")).isEqualTo(3L);
        assertThat(result.get("steps")).isEqualTo(4L);
        assertThat(result.get("failedSteps")).isEqualTo(2L);
        assertThat(noSteps.getId()).isNotNull();
    }

    /**
     * 业务键筛选按**子串**命中（`%关键词%`），且运行侧与计数侧同一口径：筛得到一条运行，
     * 那一侧回来的四个数就都围着这一条运行。
     */
    @Test
    void businessKeyFilterMatchesBySubstringOnBothSides() {
        JobRun hit = insertRun(7L, "SUCCESS");
        insertStep(hit.getId(), "msg-a", 1, "SUCCESS", "SO-2026-0917-0042");
        JobRun miss = insertRun(7L, "SUCCESS");
        insertStep(miss.getId(), "msg-b", 1, "SUCCESS", "PO-2026-0001");

        java.util.List<JobRun> direct = jobRunMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<JobRun>()
                        .apply("EXISTS (SELECT 1 FROM message_step s WHERE s.run_id = id"
                                + " AND s.business_key LIKE {0})", "%0917%"))
                .getRecords();

        Map<String, Object> result = newService().runsSummary(null, null, "0917", 1, 20);

        assertThat(result.get("runs")).isEqualTo(1L);
        assertThat(result.get("messages")).isEqualTo(1L);
        assertThat(result.get("steps")).isEqualTo(1L);
        assertThat((java.util.List<?>) result.get("summaries")).hasSize(1);
    }

    private JobRun insertRun(Long jobId, String status) {
        JobRun run = new JobRun();
        run.setJobId(jobId);
        run.setStatus(status);
        jobRunMapper.insert(run);
        return run;
    }

    private void insertStep(Long runId, String messageId, int step, String status, String businessKey) {
        MessageStep messageStep = new MessageStep();
        messageStep.setRunId(runId);
        messageStep.setMessageId(messageId);
        messageStep.setStep(step);
        messageStep.setStatus(status);
        messageStep.setBusinessKey(businessKey);
        messageStepMapper.insert(messageStep);
    }
}
