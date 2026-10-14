package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleConfig;
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
 *
 * <p>两处口径（#146）：间隔档用 {@code scheduleWithFixedDelay}——固定延迟天然保证「上一次结束后再等
 * 间隔排下一次」，与下面的运行中集合互补；固定间隔排期万一仍与一次运行撞上（手动或外部触发造成的），
 * {@link #fire(Long)} 一律跳过并只记一条日志，不产生运行行、不改任务状态。</p>
 */
@Component
public class JobScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(JobScheduler.class);
    private static final long INITIAL_DELAY_MS = 5_000L;

    private final TaskScheduler taskScheduler;
    private final JobTrigger jobTrigger;
    private final JobMapper jobMapper;
    private final ActionMapper actionMapper;
    private final JobRunGuard runGuard;
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public JobScheduler(TaskScheduler taskScheduler, JobTrigger jobTrigger,
                        JobMapper jobMapper, ActionMapper actionMapper, JobRunGuard runGuard) {
        this.taskScheduler = taskScheduler;
        this.jobTrigger = jobTrigger;
        this.jobMapper = jobMapper;
        this.actionMapper = actionMapper;
        this.runGuard = runGuard;
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
        if (!ScheduleConfig.from(job).recurring()) {
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
        ScheduleConfig config = ScheduleConfig.from(job);
        ScheduleConfig.Plan plan = config.plan();
        ScheduledFuture<?> future;
        if (plan.isFixedRate()) {
            // #146：固定延迟（不是固定间隔）——上一次运行结束之后才起算下一次的间隔
            future = taskScheduler.scheduleWithFixedDelay(
                    task, Instant.now().plusMillis(INITIAL_DELAY_MS), Duration.ofMillis(plan.fixedRateMs()));
        } else {
            future = taskScheduler.schedule(task, new CronTrigger(plan.cron(), plan.zone()));
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

    /**
     * 调度触发。已有运行在跑时**跳过**（#146）：只记日志，不产生运行行、不改任务状态。
     *
     * <p>占用在 try 之外取得、在 {@code finally} 释放，所以运行抛异常也会放锁；放的是自己那一次
     * （见 {@link JobRunGuard#release}）。</p>
     */
    private void fire(Long jobId) {
        JobRunGuard.Handle handle = runGuard.tryAcquire(jobId, JobRunGuard.ORIGIN_SCHEDULE);
        if (handle == null) {
            LOG.info("任务已有运行，跳过本次调度触发：{}", runGuard.describe(runGuard.current(jobId)));
            return;
        }
        try {
            jobTrigger.trigger(jobId, null);
        } catch (Exception e) {
            LOG.warn("任务自动调度执行失败 job={}: {}", jobId, e.getMessage());
        } finally {
            runGuard.release(handle);
        }
    }

    private String describe(Job job) {
        ScheduleConfig config = ScheduleConfig.from(job);
        return config.interval()
                ? config.intervalValue() + " " + config.intervalUnit()
                : config.scheduleWeekday() + "/" + config.scheduleTime();
    }
}
