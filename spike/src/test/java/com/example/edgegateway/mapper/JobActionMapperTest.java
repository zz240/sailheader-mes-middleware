package com.example.edgegateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MybatisPlusTest
@Sql("/schema.sql")
class JobActionMapperTest {

    @Autowired
    private JobMapper jobMapper;

    @Autowired
    private ActionMapper actionMapper;

    @Test
    void savesJobWithActionsAndReadsBack() {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName("po-sync");
        job.setStatus(1);
        job.setCrontab("0 0/30 * * * ?");
        job.setScheduleType(ScheduleType.INTERVAL);
        job.setIntervalValue(10);
        job.setIntervalUnit("MINUTES");
        job.setTimezone("Asia/Shanghai");
        job.setRetryCount(3);
        job.setBackoffMs(1000L);
        jobMapper.insert(job);

        Action read = new Action();
        read.setJobId(job.getId());
        read.setName("read");
        read.setType(ActionKind.READ_SQL);
        read.setActionIndex(1);
        actionMapper.insert(read);

        Action map = new Action();
        map.setJobId(job.getId());
        map.setName("map");
        map.setType(ActionKind.FIELD_MAP);
        map.setActionIndex(2);
        actionMapper.insert(map);

        Job found = jobMapper.selectOne(
                new LambdaQueryWrapper<Job>().eq(Job::getName, "po-sync"));
        assertThat(found.getRetryCount()).isEqualTo(3);
        assertThat(found.getScheduleType()).isEqualTo(ScheduleType.INTERVAL);
        assertThat(found.getIntervalValue()).isEqualTo(10);
        assertThat(found.getIntervalUnit()).isEqualTo("MINUTES");

        List<Action> actions = actionMapper.selectList(
                new LambdaQueryWrapper<Action>()
                        .eq(Action::getJobId, job.getId())
                        .orderByAsc(Action::getActionIndex));
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).getName()).isEqualTo("read");
        assertThat(actions.get(1).getType()).isEqualTo(ActionKind.FIELD_MAP);
    }

    @Test
    void rejectsDuplicateActionIndexWithinJob() {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName("j2");
        job.setStatus(1);
        jobMapper.insert(job);

        Action a1 = new Action();
        a1.setJobId(job.getId());
        a1.setName("x");
        a1.setType(ActionKind.READ_SQL);
        a1.setActionIndex(1);
        actionMapper.insert(a1);

        Action a2 = new Action();
        a2.setJobId(job.getId());
        a2.setName("y");
        a2.setType(ActionKind.FIELD_MAP);
        a2.setActionIndex(1);

        assertThatThrownBy(() -> actionMapper.insert(a2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesFieldMappingRulesRoundTrip() {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName("map-job");
        job.setStatus(1);
        jobMapper.insert(job);

        Action map = new Action();
        map.setJobId(job.getId());
        map.setName("map");
        map.setType(ActionKind.FIELD_MAP);
        map.setActionIndex(1);
        map.setAddition(Map.of("rules", List.of(Map.of("source", "order_no", "target", "sheet_no"))));
        actionMapper.insert(map);

        List<Action> actions = actionMapper.selectList(
                new LambdaQueryWrapper<Action>()
                        .eq(Action::getJobId, job.getId())
                        .orderByAsc(Action::getActionIndex));

        assertThat(actions.get(0).getAddition()).containsKey("rules");
    }
}
