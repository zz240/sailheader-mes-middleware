package com.example.edgegateway.mapper;

import com.example.edgegateway.support.H2MapperTest;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.model.RunStepSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@H2MapperTest
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

    private JobRun insertRun(Long jobId, String status) {
        JobRun run = new JobRun();
        run.setJobId(jobId);
        run.setStatus(status);
        jobRunMapper.insert(run);
        return run;
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
