package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import org.apache.camel.CamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 任务自动调度：把启用任务按友好调度配置（{@link ScheduleType}）注册到 Spring {@link TaskScheduler}，
 * 触发时向 {@code direct:job-<id>} 投递。不自动/停用/MQ_CONSUME 链首（消费驱动）不注册。
 * 由 {@link RouteRegistry} 在启用/停用时调用 {@link #reconcile(Long)}。
 */
@Component
public class JobScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(JobScheduler.class);
    private static final long INITIAL_DELAY_MS = 5_000L;

    private final TaskScheduler taskScheduler;
    private final CamelContext camelContext;
    private final JobMapper jobMapper;
    private final ActionMapper actionMapper;
    private final ScheduleCompiler scheduleCompiler;
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public JobScheduler(TaskScheduler taskScheduler, CamelContext camelContext,
                        JobMapper jobMapper, ActionMapper actionMapper, ScheduleCompiler scheduleCompiler) {
        this.taskScheduler = taskScheduler;
        this.camelContext = camelContext;
        this.jobMapper = jobMapper;
        this.actionMapper = actionMapper;
        this.scheduleCompiler = scheduleCompiler;
    }

    /** 依据任务当前状态登记/注销调度；由 RouteRegistry 在启用/停用时调用。 */
    public void reconcile(Long jobId) {
        Job job = jobMapper.selectById(jobId);
        if (job == null || !scheduleable(job)) {
            cancel(jobId);
            return;
        }
        register(job);
    }

    private boolean scheduleable(Job job) {
        if (job.getStatus() == null || job.getStatus() != 1) {
            return false;
        }
        if (!scheduleCompiler.recurring(job)) {
            return false;
        }
        Action first = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                        .eq(Action::getJobId, job.getId())
                        .orderByAsc(Action::getActionIndex))
                .stream().findFirst().orElse(null);
        // MQ_CONSUME 链首为消费驱动，不设定时
        return first == null || first.getType() != ActionKind.MQ_CONSUME;
    }

    private void register(Job job) {
        cancel(job.getId());
        Long id = job.getId();
        Runnable task = () -> fire(id);
        ScheduledFuture<?> future;
        if (scheduleCompiler.interval(job)) {
            long period = scheduleCompiler.fixedRateMs(job);
            future = taskScheduler.scheduleAtFixedRate(
                    task, Instant.now().plusMillis(INITIAL_DELAY_MS), Duration.ofMillis(period));
        } else {
            future = taskScheduler.schedule(task, new CronTrigger(scheduleCompiler.cron(job), scheduleCompiler.zone(job)));
        }
        futures.put(id, future);
        LOG.info("已登记任务调度 job={} schedule={}", id, describe(job));
    }

    private void cancel(Long jobId) {
        ScheduledFuture<?> future = futures.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void fire(Long jobId) {
        try (var producer = camelContext.createProducerTemplate()) {
            producer.sendBody("direct:job-" + jobId, null);
        } catch (Exception e) {
            LOG.warn("任务自动调度执行失败 job={}: {}", jobId, e.getMessage());
        }
    }

    private String describe(Job job) {
        return scheduleCompiler.interval(job)
                ? job.getIntervalValue() + " " + job.getIntervalUnit()
                : job.getScheduleWeekday() + "/" + job.getScheduleTime();
    }
}
