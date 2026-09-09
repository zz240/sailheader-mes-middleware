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
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 路由生命周期：启用任务时把配置编译成 Camel 路由注册，停用时停止并移除。
 * 启用前先做 sourceStep 引用一致性校验（{@link ChainConsistencyValidator}），不一致直接拒绝而不注册路由；
 * 启用/重建失败后的停用、写 lastError 与启动重载由 {@link RouteLifecycle} 负责。
 */
@Service
public class RouteRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(RouteRegistry.class);

    private final CamelContext camelContext;
    private final JobMapper jobMapper;
    private final ActionMapper actionMapper;
    private final RouteGenerator routeGenerator;
    private final JobScheduler jobScheduler;

    public RouteRegistry(CamelContext camelContext, JobMapper jobMapper,
                         ActionMapper actionMapper, RouteGenerator routeGenerator, JobScheduler jobScheduler) {
        this.camelContext = camelContext;
        this.jobMapper = jobMapper;
        this.actionMapper = actionMapper;
        this.routeGenerator = routeGenerator;
        this.jobScheduler = jobScheduler;
    }

    public void enable(Long jobId) {
        Job job = jobMapper.selectById(jobId);
        List<Action> actions = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .orderByAsc(Action::getActionIndex));
        // 配置 → 路由的唯一闸口（显式启用 / 变更后重建 / 启动重载都走这里）：
        // sourceStep 引用不一致（悬空或错位）在此拦截，避免生成"静默映射 null"的路由
        List<String> chainProblems = ChainConsistencyValidator.check(actions);
        if (!chainProblems.isEmpty()) {
            throw new IllegalArgumentException("动作链 sourceStep 引用不一致: "
                    + ChainConsistencyValidator.describeAll(chainProblems));
        }
        try {
            removeIfExists(routeId(jobId));
            removeIfExists(directRouteId(jobId));
            for (RouteBuilder builder : routeGenerator.build(job, actions)) { // 配置问题抛 IllegalArgumentException -> 400
                camelContext.addRoutes(builder);
            }
            jobScheduler.reconcile(jobId); // 依调度配置登记自动触发（不自动/MQ_CONSUME/停用则不登记）
        } catch (Exception e) {
            LOG.error("注册路由失败 job={}", jobId, e);
            throw new IllegalStateException("注册路由失败: " + jobId, e);
        }
    }

    public void disable(Long jobId) {
        removeIfExists(routeId(jobId));
        removeIfExists(directRouteId(jobId));
        jobScheduler.reconcile(jobId); // 停用后注销调度（reconcile 依状态判定）
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

    private String directRouteId(Long jobId) {
        return "direct-" + jobId;
    }
}
