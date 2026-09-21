package com.example.edgegateway.mapper;

import com.example.edgegateway.support.H2MapperTest;
import com.example.edgegateway.config.MybatisPlusConfig;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.model.MessageStepIndex;
import com.example.edgegateway.model.RunStepSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消息步 mapper 的真 SQL 切片。
 *
 * <p>{@code @Import(MybatisPlusConfig.class)}：`@MybatisPlusTest` 是切片，不扫生产里的
 * `@Configuration`，所以**分页拦截器默认不在**——不导入的话 `Page` 参数是个摆设
 * （total 恒为 0、LIMIT 也不加），#131 那条分页断言就成了假绿（实测踩到过）。
 * 导入之后这个切片与生产同一套分页行为。</p>
 */
@H2MapperTest
@Import(MybatisPlusConfig.class)
class MessageHistoryMapperTest {

    @Autowired
    private JobRunMapper jobRunMapper;

    @Autowired
    private MessageStepMapper messageStepMapper;

    @Test
    void savesRunAndMessageStepAndReadsBack() {
        JobRun run = new JobRun();
        run.setJobId(7L);
        run.setStatus("RUNNING");
        jobRunMapper.insert(run);

        MessageStep step = new MessageStep();
        step.setRunId(run.getId());
        step.setMessageId("msg-1");
        step.setStep(2);
        step.setActionName("map");
        step.setStatus("SUCCESS");
        step.setBusinessKey("PO-1");
        messageStepMapper.insert(step);

        JobRun found = jobRunMapper.selectById(run.getId());
        assertThat(found.getJobId()).isEqualTo(7L);
        assertThat(found.getStatus()).isEqualTo("RUNNING");

        MessageStep got = messageStepMapper.selectById(step.getId());
        assertThat(got.getMessageId()).isEqualTo("msg-1");
        assertThat(got.getStep()).isEqualTo(2);
        assertThat(got.getBusinessKey()).isEqualTo("PO-1");
    }

    @Test
    void rejectsDuplicateRunMessageStep() {
        JobRun run = new JobRun();
        run.setJobId(7L);
        run.setStatus("RUNNING");
        jobRunMapper.insert(run);

        MessageStep a = new MessageStep();
        a.setRunId(run.getId());
        a.setMessageId("m");
        a.setStep(1);
        a.setStatus("SUCCESS");
        messageStepMapper.insert(a);

        MessageStep b = new MessageStep();
        b.setRunId(run.getId());
        b.setMessageId("m");
        b.setStep(1);
        b.setStatus("FAILED");

        assertThatThrownBy(() -> messageStepMapper.insert(b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * #136：给这一页消息步补 jobId 用的那条投影查询（只取 id 与 job_id 两列）在真 SQL 上成立。
     * 这两列一起取是刻意的：job_run 上还挂着两个 TEXT 快照列，整行读回来纯属白读。
     */
    @Test
    void selectsRunJobIdsByIds() {
        JobRun run = new JobRun();
        run.setJobId(7L);
        run.setStatus("SUCCESS");
        run.setInputSnapshot("{\"big\":\"snapshot\"}");
        jobRunMapper.insert(run);

        List<JobRun> found = jobRunMapper.selectList(new LambdaQueryWrapper<JobRun>()
                .select(JobRun::getId, JobRun::getJobId)
                .in(JobRun::getId, List.of(run.getId())));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(run.getId());
        assertThat(found.get(0).getJobId()).isEqualTo(7L);
        assertThat(found.get(0).getStatus()).isNull();
    }

    /** #136：jobId 是只读投影（`@TableField(exist = false)`）——写了也不落库，读回来是空。 */
    @Test
    void jobIdIsNotAColumn() {
        JobRun run = new JobRun();
        run.setJobId(7L);
        run.setStatus("SUCCESS");
        jobRunMapper.insert(run);

        MessageStep step = new MessageStep();
        step.setRunId(run.getId());
        step.setMessageId("msg-2");
        step.setStep(1);
        step.setStatus("FAILED");
        step.setJobId(99L);
        messageStepMapper.insert(step);

        assertThat(messageStepMapper.selectById(step.getId()).getJobId()).isNull();
    }

    /**
     * #130：按 run_id 一次聚合出三个数，在真 SQL 上成立。三处口径在这一条里一起钉住：
     * 消息步数**不分状态**全算、失败步只看 FAILED、消息数按 message_id 去重
     * （一条消息穿过三个动作是 3 步 / 1 消息）。
     */
    @Test
    void summarizesStepsByRun() {
        JobRun run = insertRun(7L, "FAILED");
        insertStep(run.getId(), "msg-a", 1, "SUCCESS");
        insertStep(run.getId(), "msg-a", 2, "SUCCESS");
        insertStep(run.getId(), "msg-a", 3, "FAILED");
        insertStep(run.getId(), "msg-b", 1, "FAILED");

        List<RunStepSummary> found = messageStepMapper.summarizeByRunIds(List.of(run.getId()));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRunId()).isEqualTo(run.getId());
        assertThat(found.get(0).getStepCount()).isEqualTo(4);
        assertThat(found.get(0).getFailedSteps()).isEqualTo(2);
        assertThat(found.get(0).getMessageCount()).isEqualTo(2);
    }

    /** 一次聚合多条运行，各归各的（不是把它们加在一起）。 */
    @Test
    void summarizesEachRunSeparately() {
        JobRun first = insertRun(7L, "SUCCESS");
        insertStep(first.getId(), "msg-a", 1, "SUCCESS");
        JobRun second = insertRun(7L, "FAILED");
        insertStep(second.getId(), "msg-b", 1, "FAILED");
        insertStep(second.getId(), "msg-b", 2, "FAILED");

        List<RunStepSummary> found = messageStepMapper.summarizeByRunIds(
                List.of(first.getId(), second.getId()));

        assertThat(found).hasSize(2);
        assertThat(found.stream().filter(s -> s.getRunId().equals(first.getId())).findFirst().orElseThrow()
                .getStepCount()).isEqualTo(1);
        assertThat(found.stream().filter(s -> s.getRunId().equals(second.getId())).findFirst().orElseThrow()
                .getFailedSteps()).isEqualTo(2);
    }

    /**
     * 没有任何消息步的运行**不出现在结果里**：那是「0 消息 0 步」，由服务层补默认值。
     * 这里要是编一行 0 出来，「查不到这条运行的摘要」与「这条运行真的是空的」在服务层就分不开了。
     */
    @Test
    void omitsRunsWithoutSteps() {
        JobRun empty = insertRun(7L, "SUCCESS");
        JobRun withStep = insertRun(7L, "SUCCESS");
        insertStep(withStep.getId(), "msg-a", 1, "SUCCESS");

        List<RunStepSummary> found = messageStepMapper.summarizeByRunIds(
                List.of(empty.getId(), withStep.getId()));

        assertThat(found).extracting(RunStepSummary::getRunId).containsExactly(withStep.getId());
    }

    /**
     * #131：一条运行的消息步**索引**——只回画界面要的那几列，顺序按 id 升序（这是一条运行的步骤顺序，
     * 与检索面那边的「最新在上」不是一回事）。
     *
     * <p>「两个快照不在索引里」是**类型上**保证的（{@link MessageStepIndex} 里根本没有那两格）；
     * 这条在真 SQL 上钉住的是它的另一面：快照落没落由两个布尔列回答，且**空白算没落**。</p>
     */
    @Test
    void pagesRunStepIndexWithoutSnapshots() {
        JobRun run = insertRun(7L, "FAILED");
        MessageStep blank = insertFullStep(run.getId(), "msg-a", 1, "FAILED", "   ", null, "boom\n\tat A.b");
        MessageStep withSnapshots = insertFullStep(run.getId(), "msg-b", 2, "SUCCESS",
                "{\"in\":1}", "{\"out\":2}", null);

        Page<MessageStepIndex> page = messageStepMapper.pageRunStepIndex(new Page<>(1, 10), run.getId());

        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).extracting(MessageStepIndex::getId)
                .containsExactly(blank.getId(), withSnapshots.getId());
        assertThat(page.getRecords().get(0).getHasInputSnapshot()).isFalse();
        assertThat(page.getRecords().get(0).getHasDataSnapshot()).isFalse();
        assertThat(page.getRecords().get(1).getHasInputSnapshot()).isTrue();
        assertThat(page.getRecords().get(1).getHasDataSnapshot()).isTrue();
    }

    /** 索引行照旧带全「画得出界面」的那几列（动作、状态、错误原文、尝试、业务键、时刻）。 */
    @Test
    void runStepIndexCarriesWhatThePanelDraws() {
        JobRun run = insertRun(7L, "FAILED");
        insertFullStep(run.getId(), "msg-a", 3, "FAILED", "{\"in\":1}", null, "boom\n\tat A.b");

        MessageStepIndex row = messageStepMapper.pageRunStepIndex(new Page<>(1, 10), run.getId())
                .getRecords().get(0);

        assertThat(row.getRunId()).isEqualTo(run.getId());
        assertThat(row.getMessageId()).isEqualTo("msg-a");
        assertThat(row.getStep()).isEqualTo(3);
        assertThat(row.getActionName()).isEqualTo("map");
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getError()).isEqualTo("boom\n\tat A.b");
        assertThat(row.getAttempt()).isEqualTo(2);
        assertThat(row.getBusinessKey()).isEqualTo("PO-1");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    /**
     * 索引大快照也照样读得动（这一票的**理由**）：60 KB 的快照在 `message_step` 里是允许的
     * （TEXT 上限 64 KB），而索引那条 SQL 根本不碰那两列。这条用真 SQL 钉住「不取就不会被它拖住」
     * ——取整行那条路（检索面）在这一列上是要付钱的。
     */
    @Test
    void runStepIndexIgnoresHugeSnapshots() {
        JobRun run = insertRun(7L, "SUCCESS");
        String huge = "{\"blob\":\"" + "x".repeat(60_000) + "\"}";
        insertFullStep(run.getId(), "msg-a", 1, "SUCCESS", "{\"in\":1}", huge, null);

        MessageStepIndex row = messageStepMapper.pageRunStepIndex(new Page<>(1, 10), run.getId())
                .getRecords().get(0);

        assertThat(row.getHasDataSnapshot()).isTrue();
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
    }

    /**
     * #134：索引行带上**动作耗时**，而 V4 之前落库的老行那一格是**空**——
     * 界面据此写 `—`（没测）而不是 `0 ms`（测得是 0），这两个数在页面上必须分得开。
     */
    @Test
    void runStepIndexCarriesDurationAndKeepsOldRowsEmpty() {
        JobRun run = insertRun(7L, "SUCCESS");
        MessageStep measured = insertFullStep(run.getId(), "msg-a", 1, "SUCCESS", null, null, null);
        measured.setDurationMs(37L);
        messageStepMapper.updateById(measured);
        insertFullStep(run.getId(), "msg-b", 2, "SUCCESS", null, null, null); // 老数据：没测过

        List<MessageStepIndex> rows = messageStepMapper.pageRunStepIndex(new Page<>(1, 10), run.getId())
                .getRecords();

        assertThat(rows.get(0).getDurationMs()).isEqualTo(37L);
        assertThat(rows.get(1).getDurationMs()).isNull();
    }

    /** 分页按 id 升序翻：第 2 页接在第 1 页后面，不漏不重。 */
    @Test
    void runStepIndexPagesInStepOrder() {
        JobRun run = insertRun(7L, "SUCCESS");
        for (int i = 1; i <= 3; i += 1) {
            insertFullStep(run.getId(), "msg-" + i, i, "SUCCESS", "{\"in\":" + i + "}", null, null);
        }

        Page<MessageStepIndex> first = messageStepMapper.pageRunStepIndex(new Page<>(1, 2), run.getId());
        Page<MessageStepIndex> second = messageStepMapper.pageRunStepIndex(new Page<>(2, 2), run.getId());

        assertThat(first.getTotal()).isEqualTo(3);
        assertThat(first.getRecords()).hasSize(2);
        assertThat(second.getRecords()).hasSize(1);
        assertThat(second.getRecords().get(0).getId())
                .isGreaterThan(first.getRecords().get(1).getId());
    }

    /** 别的运行的步不进来（这一条按 runId 查，不是什么「最近 N 条」）。 */
    @Test
    void runStepIndexStaysInsideTheRun() {
        JobRun mine = insertRun(7L, "SUCCESS");
        JobRun other = insertRun(7L, "SUCCESS");
        insertFullStep(mine.getId(), "msg-a", 1, "SUCCESS", null, null, null);
        insertFullStep(other.getId(), "msg-b", 1, "FAILED", null, null, "boom");

        Page<MessageStepIndex> page = messageStepMapper.pageRunStepIndex(new Page<>(1, 10), mine.getId());

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords().get(0).getMessageId()).isEqualTo("msg-a");
    }

    private JobRun insertRun(Long jobId, String status) {
        JobRun run = new JobRun();
        run.setJobId(jobId);
        run.setStatus(status);
        jobRunMapper.insert(run);
        return run;
    }

    /** 连快照、错误、尝试、业务键一起插的完整行（索引那几条要拿它们当真数据）。 */
    private MessageStep insertFullStep(Long runId, String messageId, int step, String status,
                                       String inputSnapshot, String dataSnapshot, String error) {
        MessageStep messageStep = new MessageStep();
        messageStep.setRunId(runId);
        messageStep.setMessageId(messageId);
        messageStep.setStep(step);
        messageStep.setActionName("map");
        messageStep.setStatus(status);
        messageStep.setInputSnapshot(inputSnapshot);
        messageStep.setDataSnapshot(dataSnapshot);
        messageStep.setError(error);
        messageStep.setAttempt(2);
        messageStep.setBusinessKey("PO-1");
        messageStep.setCreatedAt(java.time.LocalDateTime.now());
        messageStepMapper.insert(messageStep);
        return messageStep;
    }

    private void insertStep(Long runId, String messageId, int step, String status) {
        insertStep(runId, messageId, step, status, null);
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
