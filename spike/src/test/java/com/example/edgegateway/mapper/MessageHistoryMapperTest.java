package com.example.edgegateway.mapper;

import com.example.edgegateway.support.H2MapperTest;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
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
}
