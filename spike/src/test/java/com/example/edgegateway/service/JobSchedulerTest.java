package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 任务自动调度：不自动/停用/MQ_CONSUME 链首不注册；间隔/每天/每周注册正确触发。 */
class JobSchedulerTest {

    private TaskScheduler taskScheduler;
    private JobMapper jobMapper;
    private ActionMapper actionMapper;
    private JobTrigger jobTrigger;
    private JobScheduler scheduler;
    private JobRunGuard runGuard;

    @BeforeEach
    void setUp() {
        taskScheduler = mock(TaskScheduler.class);
        jobMapper = mock(JobMapper.class);
        actionMapper = mock(ActionMapper.class);
        jobTrigger = mock(JobTrigger.class);
        runGuard = new JobRunGuard();
        when(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(taskScheduler.schedule(any(Runnable.class), any(Trigger.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(actionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        scheduler = new JobScheduler(taskScheduler, jobTrigger,
                jobMapper, actionMapper, runGuard);
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
        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void intervalRegistersFixedDelayWithMinutes() {
        Job job = enabledJob(ScheduleType.INTERVAL);
        job.setIntervalValue(10);
        job.setIntervalUnit("MINUTES");
        when(jobMapper.selectById(7L)).thenReturn(job);

        scheduler.reconcile(7L);

        // #146：间隔档用固定延迟（上一次结束后再等间隔），不用固定间隔
        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class),
                any(Instant.class), eq(Duration.ofMillis(10L * 60_000L)));
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
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
        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
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

        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
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

        verify(taskScheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    void fireRunsTriggerWhenJobIsIdle() {
        when(jobMapper.selectById(7L)).thenReturn(enabledJob(ScheduleType.INTERVAL));
        scheduler.reconcile(7L);

        runRegisteredTask().run();

        verify(jobTrigger).trigger(eq(7L), any());
        assertThat(runGuard.isRunning(7L)).isFalse();
    }

    @Test
    void fireSkipsWhenJobAlreadyRunning() {
        JobRunGuard.Handle occupied = runGuard.tryAcquire(7L, JobRunGuard.ORIGIN_MANUAL);
        when(jobMapper.selectById(7L)).thenReturn(enabledJob(ScheduleType.INTERVAL));
        scheduler.reconcile(7L);

        runRegisteredTask().run();

        verify(jobTrigger, never()).trigger(anyLong(), any());
        assertThat(runGuard.isRunning(7L)).isTrue();
        runGuard.release(occupied);
    }

    @Test
    void fireReleasesOccupancyWhenTriggerThrows() {
        when(jobMapper.selectById(7L)).thenReturn(enabledJob(ScheduleType.INTERVAL));
        scheduler.reconcile(7L);
        doThrow(new IllegalStateException("boom")).when(jobTrigger).trigger(eq(7L), any());

        runRegisteredTask().run();

        verify(jobTrigger).trigger(eq(7L), any());
        assertThat(runGuard.isRunning(7L)).isFalse();
    }

    /** 取 reconcile 交给 TaskScheduler 的那个触发任务，手动跑一次（不到点也能验触发语义）。 */
    private Runnable runRegisteredTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleWithFixedDelay(task.capture(), any(Instant.class), any(Duration.class));
        return task.getValue();
    }
}
