package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.Job;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 路由生命周期：启用任务时把配置编译成 Camel 路由注册，停用时停止并移除；
 * 应用启动后自动重载所有启用状态的任务。
 */
@Service
public class RouteRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(RouteRegistry.class);

    private final CamelContext camelContext;
    private final JobMapper jobMapper;
    private final ActionMapper actionMapper;
    private final RouteGenerator routeGenerator;

    public RouteRegistry(CamelContext camelContext, JobMapper jobMapper,
                         ActionMapper actionMapper, RouteGenerator routeGenerator) {
        this.camelContext = camelContext;
        this.jobMapper = jobMapper;
        this.actionMapper = actionMapper;
        this.routeGenerator = routeGenerator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reloadEnabledJobs() {
        for (Job job : jobMapper.selectList(new LambdaQueryWrapper<Job>().eq(Job::getStatus, 1))) {
            try {
                enable(job.getId());
            } catch (RuntimeException e) {
                // 单个任务配置有问题不应拖垮整个网关启动，记录后继续
                LOG.warn("启动时重载任务路由失败，跳过 job={}: {}", job.getId(), e.getMessage());
            }
        }
    }

    public void enable(Long jobId) {
        Job job = jobMapper.selectById(jobId);
        List<Action> actions = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .orderByAsc(Action::getActionIndex));
        RouteBuilder builder = routeGenerator.build(job, actions); // 配置问题抛 IllegalArgumentException -> 400
        try {
            removeIfExists(routeId(jobId));
            camelContext.addRoutes(builder);
        } catch (Exception e) {
            throw new IllegalStateException("注册路由失败: " + jobId, e);
        }
    }

    public void disable(Long jobId) {
        removeIfExists(routeId(jobId));
    }

    private void removeIfExists(String routeId) {
        try {
            if (camelContext.getRoute(routeId) != null) {
                camelContext.getRouteController().stopRoute(routeId);
                camelContext.removeRoute(routeId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("移除路由失败: " + routeId, e);
        }
    }

    private String routeId(Long jobId) {
        return "job-" + jobId;
    }
}
