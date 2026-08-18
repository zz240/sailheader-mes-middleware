package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.DeadLetterMapper;
import com.example.edgegateway.model.DeadLetter;
import com.example.edgegateway.model.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 死信服务：动作最终失败后记录（任务、动作序号、输入快照、错误），
 * 供人工查看与重投（重投在 T17）。
 */
@Service
public class DeadLetterService {

    private final DeadLetterMapper deadLetterMapper;
    private final ObjectMapper objectMapper;
    private final JobExecutionService jobExecutionService;

    public DeadLetterService(
            DeadLetterMapper deadLetterMapper,
            ObjectMapper objectMapper,
            JobExecutionService jobExecutionService) {
        this.deadLetterMapper = deadLetterMapper;
        this.objectMapper = objectMapper;
        this.jobExecutionService = jobExecutionService;
    }

    public void record(Long jobId, Integer actionIndex, Object input, Exception error) {
        DeadLetter deadLetter = new DeadLetter();
        deadLetter.setJobId(jobId);
        deadLetter.setActionIndex(actionIndex);
        deadLetter.setInputJson(toJson(input));
        deadLetter.setError(error == null ? null : error.getMessage());
        deadLetter.setStatus("FAILED");
        deadLetterMapper.insert(deadLetter);
    }

    public List<DeadLetter> list(Long jobId, String status) {
        LambdaQueryWrapper<DeadLetter> wrapper = new LambdaQueryWrapper<DeadLetter>()
                .orderByDesc(DeadLetter::getId);
        if (jobId != null) {
            wrapper.eq(DeadLetter::getJobId, jobId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(DeadLetter::getStatus, status.trim());
        }
        return deadLetterMapper.selectList(wrapper);
    }

    public DeadLetter get(Long id) {
        DeadLetter deadLetter = deadLetterMapper.selectById(id);
        if (deadLetter == null) {
            throw new NoSuchElementException("死信不存在: " + id);
        }
        return deadLetter;
    }

    /** 重投：把死信输入重新送入任务执行；成功后死信标记 REDELIVERED，失败保持 FAILED 并抛出。 */
    public Job redeliver(Long id) {
        DeadLetter deadLetter = get(id);
        Map<String, Object> input = parseInput(deadLetter.getInputJson());
        Job job = jobExecutionService.execute(deadLetter.getJobId(), input);
        if ("FAILED".equals(job.getLastRunStatus())) {
            throw new IllegalStateException("重投失败: " + job.getLastError());
        }
        deadLetter.setStatus("REDELIVERED");
        deadLetterMapper.updateById(deadLetter);
        return job;
    }

    private Map<String, Object> parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(inputJson, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("死信输入不是 JSON 对象，无法重投", e);
        }
    }

    private String toJson(Object input) {
        if (input == null) {
            return null;
        }
        try {
            if (input instanceof String s) {
                return s;
            }
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }
}
