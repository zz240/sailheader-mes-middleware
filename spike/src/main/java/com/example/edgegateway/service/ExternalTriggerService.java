package com.example.edgegateway.service;

import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.JobView;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 外部任务触发：校验站点 API Key（X-API-Key）与任务所属站点匹配，
 * 通过后复用手动执行逻辑（任务需处于启用状态）。
 */
@Service
public class ExternalTriggerService {

    private final JobMapper jobMapper;
    private final ApiKeyService apiKeyService;
    private final JobExecutionService jobExecutionService;

    public ExternalTriggerService(
            JobMapper jobMapper,
            ApiKeyService apiKeyService,
            JobExecutionService jobExecutionService) {
        this.jobMapper = jobMapper;
        this.apiKeyService = apiKeyService;
        this.jobExecutionService = jobExecutionService;
    }

    public JobView trigger(Long jobId, String apiKey, Map<String, Object> input) {
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new NoSuchElementException("任务不存在: " + jobId);
        }
        if (!apiKeyService.matches(job.getSiteId(), apiKey)) {
            throw BusinessException.of(BusinessCode.UNAUTHORIZED, "API Key 缺失或无效");
        }
        return JobView.from(jobExecutionService.execute(jobId, input));
    }
}
