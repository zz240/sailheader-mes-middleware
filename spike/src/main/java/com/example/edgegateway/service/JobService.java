package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleConfig;
import com.example.edgegateway.web.dto.JobRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 任务业务：CRUD、启停与配置校验（名称必填、站点必填、方向必填、调度配置合法、同站点重名冲突）。
 * 调度配置为结构化友好输入，校验/编译收敛到 {@link ScheduleConfig}；保存时把系统 cron（仅 DAILY/WEEKLY）
 * 写入 {@code crontab} 列。
 * 若任务处于启用状态，保存后经 {@link RouteLifecycle} 自动重建路由与调度（失败则自动停用），使新配置立即可生效。
 */
@Service
public class JobService {

    private final JobMapper jobMapper;
    private final MappingSampleService mappingSampleService;
    private final RouteLifecycle routeLifecycle;

    public JobService(JobMapper jobMapper, MappingSampleService mappingSampleService, RouteLifecycle routeLifecycle) {
        this.jobMapper = jobMapper;
        this.mappingSampleService = mappingSampleService;
        this.routeLifecycle = routeLifecycle;
    }

    public Job create(JobRequest request) {
        validate(request);
        if (exists(request.siteId(), request.name(), null)) {
            throw BusinessException.of(BusinessCode.DUPLICATE_NAME, "任务名称已存在: " + request.name());
        }
        Job job = toEntity(request);
        jobMapper.insert(job);
        routeLifecycle.rebuildIfEnabled(job.getId());
        return job;
    }

    public Job update(Long id, JobRequest request) {
        validate(request);
        Job existing = get(id);
        if (exists(request.siteId(), request.name(), id)) {
            throw BusinessException.of(BusinessCode.DUPLICATE_NAME, "任务名称已存在: " + request.name());
        }
        apply(existing, request);
        jobMapper.updateById(existing);
        routeLifecycle.rebuildIfEnabled(existing.getId());
        return existing;
    }

    public void delete(Long id) {
        get(id);
        jobMapper.deleteById(id);
        // 任务删除后，它下面每一格的样例也一并删除（#119：不保留残留行）
        mappingSampleService.clearOfJob(id);
    }

    public Job get(Long id) {
        Job job = jobMapper.selectById(id);
        if (job == null) {
            throw new NoSuchElementException("任务不存在: " + id);
        }
        return job;
    }

    public List<Job> list(String name, Integer status) {
        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<>();
        if (name != null && !name.isBlank()) {
            wrapper.like(Job::getName, name);
        }
        if (status != null) {
            wrapper.eq(Job::getStatus, status);
        }
        return jobMapper.selectList(wrapper);
    }

    public Job setStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("状态只能是 0（停用）或 1（启用）");
        }
        Job job = get(id);
        job.setStatus(status);
        jobMapper.updateById(job);
        return job;
    }

    private boolean exists(Long siteId, String name, Long excludeId) {
        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<Job>()
                .eq(Job::getSiteId, siteId)
                .eq(Job::getName, name);
        if (excludeId != null) {
            wrapper.ne(Job::getId, excludeId);
        }
        return jobMapper.selectCount(wrapper) > 0;
    }

    private void validate(JobRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        if (request.siteId() == null) {
            throw new IllegalArgumentException("站点不能为空");
        }
        if (request.direction() == null) {
            throw new IllegalArgumentException("任务需要声明方向（NORTH/SOUTH）");
        }
    }

    private Job toEntity(JobRequest request) {
        Job job = new Job();
        apply(job, request);
        return job;
    }

    private void apply(Job job, JobRequest request) {
        job.setSiteId(request.siteId());
        job.setName(request.name());
        job.setStatus(request.status() == null ? 0 : request.status());
        job.setDirection(request.direction());
        // 校验 + 收敛扁平字段；非法调度在此抛 IllegalArgumentException（消息可定位）
        ScheduleConfig schedule = ScheduleConfig.of(
                request.scheduleType(), request.intervalValue(), request.intervalUnit(),
                request.scheduleTime(), request.scheduleWeekday(), request.timezone());
        job.setSchedule(schedule);
        // 系统生成的内部 cron（仅 DAILY/WEEKLY；INTERVAL/NONE 为 null），不暴露给用户编辑
        job.setCrontab(schedule.internalCron());
        job.setRetryCount(request.retryCount());
        job.setBackoffMs(request.backoffMs());
        job.setRemarks(request.remarks());
    }

}
