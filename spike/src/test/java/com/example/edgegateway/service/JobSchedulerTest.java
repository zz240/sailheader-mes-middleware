package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleType;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 任务自动调度：不自动/停用/MQ_CONSUME 链首不注册；间隔/每天/每周注册正确触发。 */
class JobSchedulerTest {

    private TaskScheduler taskScheduler;
    private JobMapper jobMapper;
    private ActionMapper actionMapper;
    private CamelContext camelContext;
    private JobScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskScheduler = mock(TaskScheduler.class);
        jobMapper = mock(JobMapper.class);
        actionMapper = mock(ActionMapper.class);
        camelContext = mock(CamelContext.class);
        when(camelContext.createProducerTemplate()).thenReturn(mock(ProducerTemplate.class));
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(actionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        scheduler = new JobScheduler(taskScheduler, camelContext,
                jobMapper, actionMapper, new ScheduleCompiler());
    }

    private Job enabledJob(ScheduleType type) {
        Job job = new Job();
        job.setId(7L);
        job.setStatus(1);
        job.setScheduleType(type);
        return job;
    }

    @Test
    void noneScheduleRegistersNothing() {
        when(jobMapper.selectById(7L)).thenReturn(enabledJob(ScheduleType.NONE));
        scheduler.reconcile(7L);
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void intervalRegistersFixedRateWithMinutes() {
        Job job = enabledJob(ScheduleType.INTERVAL);
        job.setIntervalValue(10);
        job.setIntervalUnit("MINUTES");
        when(jobMapper.selectById(7L)).thenReturn(job);

        scheduler.reconcile(7L);

        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class),
                any(Instant.class), eq(Duration.ofMillis(10L * 60_000L)));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void dailyRegistersCronTrigger() {
        Job job = enabledJob(ScheduleType.DAILY);
        job.setScheduleTime("08:30");
        job.setTimezone("Asia/Shanghai");
        when(jobMapper.selectById(7L)).thenReturn(job);

        scheduler.reconcile(7L);

        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    void weeklyRegistersCronTrigger() {
        Job job = enabledJob(ScheduleType.WEEKLY);
        job.setScheduleWeekday(5);
        job.setScheduleTime("18:05");
        when(jobMapper.selectById(7L)).thenReturn(job);

        scheduler.reconcile(7L);

        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void disabledJobRegistersNothing() {
        Job job = enabledJob(ScheduleType.INTERVAL);
        job.setIntervalValue(5);
        job.setIntervalUnit("MINUTES");
        job.setStatus(0);
        when(jobMapper.selectById(7L)).thenReturn(job);

        scheduler.reconcile(7L);

        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    void mqConsumeChainHeadRegistersNothing() {
        Job job = enabledJob(ScheduleType.INTERVAL);
        job.setIntervalValue(5);
        job.setIntervalUnit("MINUTES");
        when(jobMapper.selectById(7L)).thenReturn(job);
        Action consume = new Action();
        consume.setType(ActionKind.MQ_CONSUME);
        when(actionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(consume));

        scheduler.reconcile(7L);

        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }
}
