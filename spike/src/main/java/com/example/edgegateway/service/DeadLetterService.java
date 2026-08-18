package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.DeadLetterMapper;
import com.example.edgegateway.model.DeadLetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

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

    public DeadLetterService(DeadLetterMapper deadLetterMapper, ObjectMapper objectMapper) {
        this.deadLetterMapper = deadLetterMapper;
        this.objectMapper = objectMapper;
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
