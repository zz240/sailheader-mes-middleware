package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import org.springframework.stereotype.Component;

/**
 * 路由/调度生命周期的协调点：把“启用任务在配置变更后自动重建路由与调度，失败则自动停用并抛出”这份策略
 * 收拢到一处（原来在 {@link JobService} 与 {@link ActionService} 各自重复）。
 * 停用/启用的显式切换仍由 {@link RouteRegistry} 承载（控制器直连）；本 module 只负责“变更后重建”。
 */
@Component
public class RouteLifecycle {

    private final JobMapper jobMapper;
    private final RouteRegistry routeRegistry;

    public RouteLifecycle(JobMapper jobMapper, RouteRegistry routeRegistry) {
        this.jobMapper = jobMapper;
        this.routeRegistry = routeRegistry;
    }

    /** 若任务处于启用状态则重建其路由与调度；重建失败置任务停用并抛 {@link IllegalArgumentException}。 */
    public void rebuildIfEnabled(Long jobId) {
        Job job = jobMapper.selectById(jobId);
        if (job == null || job.getStatus() == null || job.getStatus() != 1) {
            return;
        }
        try {
            routeRegistry.enable(jobId);
        } catch (RuntimeException e) {
            job.setStatus(0);
            jobMapper.updateById(job);
            throw new IllegalArgumentException("路由重建失败，任务已自动停用: " + e.getMessage(), e);
        }
    }
}
