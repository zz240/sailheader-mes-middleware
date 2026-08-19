package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.JobRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 任务业务：CRUD、启停与配置校验（名称必填、站点必填、水位策略必须配列名、同站点重名冲突）。
 */
@Service
public class JobService {

    private final JobMapper jobMapper;

    public JobService(JobMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    public Job create(JobRequest request) {
        validate(request);
        if (exists(request.siteId(), request.name(), null)) {
            throw BusinessException.of(BusinessCode.DUPLICATE_NAME, "任务名称已存在: " + request.name());
        }
        Job job = toEntity(request);
        jobMapper.insert(job);
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
        if (request.watermarkType() != null
                && (request.watermarkColumn() == null || request.watermarkColumn().isBlank())) {
            throw new IllegalArgumentException("设置了水位策略必须填写 watermarkColumn");
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
        job.setCrontab(request.crontab());
        job.setTimezone(request.timezone());
        job.setRetryCount(request.retryCount());
        job.setBackoffMs(request.backoffMs());
        job.setWatermarkType(request.watermarkType());
        job.setWatermarkColumn(request.watermarkColumn());
        job.setRemarks(request.remarks());
    }
}
