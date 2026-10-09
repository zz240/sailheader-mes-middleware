package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.MappingSampleMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.MappingSample;
import com.example.edgegateway.model.MappingSampleKind;
import com.example.edgegateway.support.H2MapperTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删任务要把它下面的动作行与样例行一起删除（不保留残留行）；别的任务的行不受影响。
 *
 * <p>断言看的是**表里剩下的行**（真库切片 H2 + schema.sql），不是"某个方法被调了一次"。</p>
 */
@H2MapperTest
@Import({JobService.class, MappingSampleService.class})
class JobDeleteCascadeTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobMapper jobMapper;

    @Autowired
    private ActionMapper actionMapper;

    @Autowired
    private MappingSampleMapper mappingSampleMapper;

    @MockBean
    private RouteLifecycle routeLifecycle;

    @Test
    void deletingAJobTakesItsActionsAndSamplesWithIt() {
        Long jobId = insertJob("SH-DELETE-ME");
        Long otherJobId = insertJob("SH-KEEP-ME");
        insertAction(jobId, 1, ActionKind.READ_SQL);
        insertAction(jobId, 2, ActionKind.HTTP_CALL);
        insertSample(jobId, MappingSampleKind.SOURCE, 1L);
        insertAction(otherJobId, 1, ActionKind.READ_SQL);

        jobService.delete(jobId);

        assertThat(jobMapper.selectById(jobId)).as("任务本身没了").isNull();
        assertThat(actionsOf(jobId)).as("它下面的动作行跟着走").isZero();
        assertThat(samplesOf(jobId)).as("它下面的样例行跟着走").isZero();
        assertThat(actionsOf(otherJobId)).as("别的任务的动作行不能被连坐").isEqualTo(1L);
    }

    private Long insertJob(String name) {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName(name);
        job.setStatus(0);
        jobMapper.insert(job);
        return job.getId();
    }

    private void insertAction(Long jobId, int index, ActionKind kind) {
        Action action = new Action();
        action.setJobId(jobId);
        action.setName("a" + index);
        action.setType(kind);
        action.setActionIndex(index);
        actionMapper.insert(action);
    }

    private void insertSample(Long jobId, MappingSampleKind kind, Long refId) {
        MappingSample sample = new MappingSample();
        sample.setJobId(jobId);
        sample.setKind(kind);
        sample.setRefId(refId);
        sample.setSampleJson("{}");
        mappingSampleMapper.insert(sample);
    }

    private Long actionsOf(Long jobId) {
        return actionMapper.selectCount(new LambdaQueryWrapper<Action>().eq(Action::getJobId, jobId));
    }

    private Long samplesOf(Long jobId) {
        return mappingSampleMapper.selectCount(
                new LambdaQueryWrapper<MappingSample>().eq(MappingSample::getJobId, jobId));
    }
}
