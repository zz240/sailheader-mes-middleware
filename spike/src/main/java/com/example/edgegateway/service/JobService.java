package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.ScheduleType;
import com.example.edgegateway.web.dto.JobRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 任务业务：CRUD、启停与配置校验（名称必填、站点必填、方向必填、调度配置合法、同站点重名冲突）。
 * 调度配置为结构化友好输入；保存时内部编译出系统 cron（仅 DAILY/WEEKLY）写入 {@code crontab} 列。
 * 若任务处于启用状态，保存后自动重建路由与调度（失败则自动停用），使新配置立即可生效。
 */
@Service
public class JobService {

    private static final Set<String> INTERVAL_UNITS = Set.of("MINUTES", "HOURS");

    private final JobMapper jobMapper;
    private final ScheduleCompiler scheduleCompiler;
    private final RouteRegistry routeRegistry;

    public JobService(JobMapper jobMapper, ScheduleCompiler scheduleCompiler, RouteRegistry routeRegistry) {
        this.jobMapper = jobMapper;
        this.scheduleCompiler = scheduleCompiler;
        this.routeRegistry = routeRegistry;
    }

    public Job create(JobRequest request) {
        validate(request);
        if (exists(request.siteId(), request.name(), null)) {
            throw BusinessException.of(BusinessCode.DUPLICATE_NAME, "任务名称已存在: " + request.name());
        }
        Job job = toEntity(request);
        jobMapper.insert(job);
        rebuildRoutesIfEnabled(job);
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
        rebuildRoutesIfEnabled(existing);
        return existing;
    }

    public void delete(Long id) {
        get(id);
        jobMapper.deleteById(id);
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
        validateSchedule(request);
    }

    /** 调度友好配置校验：非正间隔、非法时间、未选周几等给出可定位错误。 */
    private void validateSchedule(JobRequest request) {
        ScheduleType type = request.scheduleType() == null ? ScheduleType.NONE : request.scheduleType();
        switch (type) {
            case NONE -> {
                // 不自动：无需其它字段
            }
            case INTERVAL -> {
                if (request.intervalValue() == null) {
                    throw new IllegalArgumentException("定时间隔值不能为空");
                }
                if (request.intervalValue() <= 0) {
                    throw new IllegalArgumentException("定时间隔必须为正整数");
                }
                if (!INTERVAL_UNITS.contains(request.intervalUnit())) {
                    throw new IllegalArgumentException("定时单位只能是 MINUTES 或 HOURS");
                }
            }
            case DAILY -> {
                validateTime(request.scheduleTime(), "每日");
            }
            case WEEKLY -> {
                if (request.scheduleWeekday() == null || request.scheduleWeekday() < 1 || request.scheduleWeekday() > 7) {
                    throw new IllegalArgumentException("请选择周几（1=周一 … 7=周日）");
                }
                validateTime(request.scheduleTime(), "每周");
            }
            default -> throw new IllegalArgumentException("不支持的调度方式: " + type);
        }
    }

    private void validateTime(String time, String label) {
        if (time == null || time.isBlank()) {
            throw new IllegalArgumentException(label + "执行时间不能为空");
        }
        if (time.length() != 5 || time.charAt(2) != ':') {
            throw new IllegalArgumentException(label + "执行时间必须是 HH:MM");
        }
        try {
            int hour = Integer.parseInt(time.substring(0, 2));
            int minute = Integer.parseInt(time.substring(3, 5));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "执行时间合法范围 00:00-23:59: " + time);
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
        job.setScheduleType(request.scheduleType() == null ? ScheduleType.NONE : request.scheduleType());
        job.setIntervalValue(request.intervalValue());
        job.setIntervalUnit(request.intervalUnit());
        job.setScheduleTime(request.scheduleTime());
        job.setScheduleWeekday(request.scheduleWeekday());
        job.setTimezone(request.timezone());
        job.setRetryCount(request.retryCount());
        job.setBackoffMs(request.backoffMs());
        job.setRemarks(request.remarks());
        // 系统生成的内部 cron（仅 DAILY/WEEKLY；INTERVAL/NONE 为 null），不暴露给用户编辑
        job.setCrontab(scheduleCompiler.cron(job));
    }

    /**
     * 启用态任务保存后自动重建路由与调度；重建失败自动停用，避免"启用但无路由/调度"的悬空状态。
     * 低于 ActionService 的既有语义（动作变更后启用态同样重建）。
     */
    private void rebuildRoutesIfEnabled(Job job) {
        if (job.getStatus() == null || job.getStatus() != 1) {
            return;
        }
        try {
            routeRegistry.enable(job.getId());
        } catch (RuntimeException e) {
            job.setStatus(0);
            jobMapper.updateById(job);
            throw new IllegalArgumentException("任务已保存，但路由重建失败，任务已自动停用: " + e.getMessage(), e);
        }
    }
}
