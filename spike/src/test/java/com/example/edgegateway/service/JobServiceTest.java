package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleType;
import com.example.edgegateway.model.SyncDirection;
import com.example.edgegateway.web.dto.JobRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobServiceTest {

    private JobMapper mapper;
    private JobService service;
    private MappingSampleService mappingSampleService;
    private RouteLifecycle routeLifecycle;

    @BeforeEach
    void setUp() {
        mapper = mock(JobMapper.class);
        mappingSampleService = mock(MappingSampleService.class);
        routeLifecycle = mock(RouteLifecycle.class);
        service = new JobService(mapper, mappingSampleService, routeLifecycle);
    }

    private JobRequest request(String name) {
        return new JobRequest(name, 1L, 0, SyncDirection.NORTH, ScheduleType.NONE,
                null, null, null, null, "Asia/Shanghai", 3, 1000L, "备注");
    }

    @Test
    void createsValidJob() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(Job.class))).thenAnswer(inv -> {
            inv.getArgument(0, Job.class).setId(1L);
            return 1;
        });

        Job saved = service.create(request("po-sync"));

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getName()).isEqualTo("po-sync");
        // 保存后交由 RouteLifecycle 按启用状态决定是否重建（此处为委托）
        verify(routeLifecycle).rebuildIfEnabled(1L);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> service.create(request("")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateNameInSite() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("dup")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingDirection() {
        JobRequest req = new JobRequest("job", 1L, 0, null, ScheduleType.NONE,
                null, null, null, null, null, 0, 0L, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("方向");
    }

    @Test
    void rejectsNonPositiveIntervalValue() {
        JobRequest req = new JobRequest("job", 1L, 0, SyncDirection.NORTH, ScheduleType.INTERVAL,
                0, "MINUTES", null, null, null, 0, 0L, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正整数");
    }

    @Test
    void rejectsInvalidIntervalUnit() {
        JobRequest req = new JobRequest("job", 1L, 0, SyncDirection.NORTH, ScheduleType.INTERVAL,
                5, "SECONDS", null, null, null, 0, 0L, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MINUTES");
    }

    @Test
    void rejectsInvalidDailyTime() {
        JobRequest req = new JobRequest("job", 1L, 0, SyncDirection.NORTH, ScheduleType.DAILY,
                null, null, "25:99", null, null, 0, 0L, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行时间");
    }

    @Test
    void rejectsWeeklyWithoutWeekday() {
        JobRequest req = new JobRequest("job", 1L, 0, SyncDirection.NORTH, ScheduleType.WEEKLY,
                null, null, "09:00", null, null, 0, 0L, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("周几");
    }

    @Test
    void persistsInternalCronForDaily() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(Job.class))).thenAnswer(inv -> {
            inv.getArgument(0, Job.class).setId(1L);
            return 1;
        });
        JobRequest req = new JobRequest("job", 1L, 0, SyncDirection.NORTH, ScheduleType.DAILY,
                null, null, "08:30", null, "Asia/Shanghai", 0, 0L, null);

        Job saved = service.create(req);

        assertThat(saved.getScheduleType()).isEqualTo(ScheduleType.DAILY);
        assertThat(saved.getCrontab()).isEqualTo("0 30 8 * * *");
        verify(routeLifecycle).rebuildIfEnabled(1L);
    }

    @Test
    void reconcilesEnabledJobAfterSave() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(Job.class))).thenAnswer(inv -> {
            inv.getArgument(0, Job.class).setId(1L);
            return 1;
        });
        JobRequest req = new JobRequest("job", 1L, 1, SyncDirection.NORTH, ScheduleType.NONE,
                null, null, null, null, null, 0, 0L, null);

        service.create(req);

        verify(routeLifecycle).rebuildIfEnabled(1L);
    }

    @Test
    void setsStatus() {
        Job job = new Job();
        job.setId(1L);
        job.setName("j");
        job.setStatus(0);
        when(mapper.selectById(1L)).thenReturn(job);

        Job updated = service.setStatus(1L, 1);

        assertThat(updated.getStatus()).isEqualTo(1);
    }

    /** 任务删除后，它下面每一格的样例也一并删除（#119：不保留残留行）。 */
    @Test
    void clearsStoredSamplesOfADeletedJob() {
        Job job = new Job();
        job.setId(1L);
        when(mapper.selectById(1L)).thenReturn(job);

        service.delete(1L);

        verify(mappingSampleService).clearOfJob(1L);
    }
}
