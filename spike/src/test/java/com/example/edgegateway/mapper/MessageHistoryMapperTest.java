package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisPlusTest
@Sql("/schema.sql")
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
}
