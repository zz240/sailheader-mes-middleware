package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 路由/调度生命周期的协调点：把“启用任务失败（显式启用、配置变更后重建、启动重载）则自动停用 +
 * 摘除路由与调度 + 写 {@code job.lastError} 并抛出”这份策略收拢到一处
 * （原来散在 {@link JobService}、{@link ActionService}、{@link DataSourceService}、{@link RouteRegistry}）。
 * 路由与调度的机械操作仍由 {@link RouteRegistry} 承载；本类只负责“启用/重建的失败语义”。
 */
@Component
public class RouteLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(RouteLifecycle.class);

    private final JobMapper jobMapper;
    private final RouteRegistry routeRegistry;

    public RouteLifecycle(JobMapper jobMapper, RouteRegistry routeRegistry) {
        this.jobMapper = jobMapper;
        this.routeRegistry = routeRegistry;
    }

    /**
     * 启动后重载启用任务（{@code @Order(3)}：在数据源迁移/连接池就绪之后）。
     * 配置不一致的任务（历史遗留的悬空 sourceStep）在此自动停用并写明原因，不留“已启用但无路由”的僵尸任务；
     * 其余失败（如 broker 暂不可达）只记录并保留启用状态，等下次配置变更或手动启用时重建——避免启动期瞬时问题批量停用。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(3)
    public void reloadEnabledJobs() {
        for (Job job : jobMapper.selectList(new LambdaQueryWrapper<Job>().eq(Job::getStatus, 1))) {
            try {
                // RouteRegistry.enable 只在 sourceStep 引用不一致时抛 IllegalArgumentException，其余失败包成 IllegalStateException
                routeRegistry.enable(job.getId());
            } catch (IllegalArgumentException e) {
                autoDisable(job, e.getMessage());
                LOG.warn("启动时配置校验失败，任务已自动停用 job={}: {}", job.getId(), e.getMessage());
            } catch (RuntimeException e) {
                LOG.warn("启动时重载任务路由失败，跳过 job={}: {}", job.getId(), e.getMessage());
            }
        }
    }

    /** 显式启用任务：失败自动停用并写 lastError 后抛 {@link IllegalArgumentException}（与重建同一套语义）。 */
    public void enable(Long jobId) {
        enableOrAutoDisable(jobMapper.selectById(jobId), jobId, "启用失败");
    }

    /** 若任务处于启用状态则重建其路由与调度；失败自动停用 + 写 lastError + 摘除路由/调度后抛出。 */
    public void rebuildIfEnabled(Long jobId) {
        Job job = jobMapper.selectById(jobId);
        if (job == null || job.getStatus() == null || job.getStatus() != 1) {
            return;
        }
        enableOrAutoDisable(job, jobId, "路由重建失败");
    }

    private void enableOrAutoDisable(Job job, Long jobId, String action) {
        try {
            routeRegistry.enable(jobId);
        } catch (RuntimeException e) {
            autoDisable(job, e.getMessage());
            throw new IllegalArgumentException(action + "，任务已自动停用: " + e.getMessage(), e);
        }
    }

    /**
     * 停用 + 摘除路由/调度 + 把失败原因落到 {@code job.lastError}（任务列表/详情直接可见，不必翻日志）。
     * 先落库停用状态：{@link RouteRegistry#disable} 内的调度 reconcile 依状态注销。
     */
    private void autoDisable(Job job, String error) {
        if (job == null) {
            return;
        }
        job.setStatus(0);
        job.setLastError(error);
        jobMapper.updateById(job);
        try {
            routeRegistry.disable(job.getId());
        } catch (RuntimeException e) {
            // 摘除失败不能掩盖原始失败原因
            LOG.warn("自动停用后摘除路由/调度失败 job={}: {}", job.getId(), e.getMessage());
        }
    }
}
