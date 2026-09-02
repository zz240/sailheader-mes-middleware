package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.StreamCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 消息历史服务：把死信升维为"运行 + 消息步"（ADR-0009）。
 * 负责在任务执行链路上记录每次运行与每条消息穿过每个动作的结果（成功/失败全程），
 * 供历史页面按运行/消息/业务键检索，并支持从失败消息步精确断点续跑（人工重投）。
 */
@Service
public class MessageHistoryService {

    private static final Logger LOG = LoggerFactory.getLogger(MessageHistoryService.class);

    public static final String RUN_ID = "edge.runId";
    public static final String MESSAGE_ID = "edge.messageId";
    public static final String BUSINESS_KEY = "edge.businessKey";
    public static final String ACTION_INDEX = "edge.actionIndex";
    public static final String ACTION_NAME = "edge.actionName";
    public static final String STEP_INPUT = "edge.stepInput";
    public static final String RESUME_FROM = "edge.resumeFromStep";
    /** READ_SQL 源动作在失败（未产生任何源行）时用作消息占位 id，让失败也能落一条 FAILED 消息步。 */
    public static final String BATCH_MSG_ID = "edge.batchMessageId";

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String REDELIVERED = "REDELIVERED";

    private final JobRunMapper jobRunMapper;
    private final MessageStepMapper messageStepMapper;
    private final JobMapper jobMapper;
    private final ObjectMapper objectMapper;
    private final CamelContext camelContext;

    public MessageHistoryService(
            JobRunMapper jobRunMapper,
            MessageStepMapper messageStepMapper,
            JobMapper jobMapper,
            ObjectMapper objectMapper,
            CamelContext camelContext) {
        this.jobRunMapper = jobRunMapper;
        this.messageStepMapper = messageStepMapper;
        this.jobMapper = jobMapper;
        this.objectMapper = objectMapper;
        this.camelContext = camelContext;
    }

    // ---------- 运行（job_run） ----------

    public JobRun startRun(Long jobId, Object input) {
        JobRun run = new JobRun();
        run.setJobId(jobId);
        run.setStatus("RUNNING");
        run.setInputSnapshot(toJson(input));
        run.setStartedAt(LocalDateTime.now());
        jobRunMapper.insert(run);
        return run;
    }

    public void finishRun(Long runId, String status, String error) {
        if (runId == null) {
            return;
        }
        JobRun run = jobRunMapper.selectById(runId);
        if (run == null || "SUCCESS".equals(run.getStatus()) || "FAILED".equals(run.getStatus())) {
            return; // 已完成不受重复 finish 影响
        }
        run.setStatus(status);
        run.setError(error);
        run.setFinishedAt(LocalDateTime.now());
        jobRunMapper.updateById(run);
    }

    // ---------- 消息步（message_step） ----------

    public void recordSuccess(Long runId, String messageId, Integer step, String actionName,
                              Object input, Object data, String businessKey, int attempt) {
        record(runId, messageId, step, actionName, SUCCESS, data, input, null, businessKey, attempt);
    }

    public void recordFailure(Long runId, String messageId, Integer step, String actionName,
                              String error, Object input, String businessKey, int attempt) {
        record(runId, messageId, step, actionName, FAILED, null, input, error, businessKey, attempt);
    }

    private void record(Long runId, String messageId, Integer step, String actionName, String status,
                        Object data, Object input, String error, String businessKey, int attempt) {
        if (runId == null || messageId == null || step == null) {
            return;
        }
        MessageStep existing = messageStepMapper.selectOne(new LambdaQueryWrapper<MessageStep>()
                .eq(MessageStep::getRunId, runId)
                .eq(MessageStep::getMessageId, messageId)
                .eq(MessageStep::getStep, step));
        MessageStep entity = existing == null ? new MessageStep() : existing;
        entity.setRunId(runId);
        entity.setMessageId(messageId);
        entity.setStep(step);
        entity.setActionName(actionName);
        entity.setStatus(status);
        entity.setDataSnapshot(toJson(data));
        entity.setInputSnapshot(toJson(input));
        entity.setError(error);
        entity.setAttempt(attempt);
        entity.setBusinessKey(businessKey);
        if (existing == null) {
            entity.setCreatedAt(LocalDateTime.now());
            messageStepMapper.insert(entity);
        } else {
            messageStepMapper.updateById(entity);
        }
    }

    // ---------- 检索 ----------

    public Map<String, Object> listRuns(Long jobId, String status, int page, int size) {
        LambdaQueryWrapper<JobRun> wrapper = new LambdaQueryWrapper<JobRun>()
                .orderByDesc(JobRun::getId);
        if (jobId != null) {
            wrapper.eq(JobRun::getJobId, jobId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(JobRun::getStatus, status.trim());
        }
        Page<JobRun> result = jobRunMapper.selectPage(new Page<>(page, size), wrapper);
        return pageOf(result.getTotal(), result.getRecords());
    }

    public Map<String, Object> listSteps(Long jobId, Long runId, String messageId, String businessKey,
                                         String status, int page, int size) {
        LambdaQueryWrapper<MessageStep> wrapper = new LambdaQueryWrapper<MessageStep>()
                .orderByDesc(MessageStep::getId);
        if (jobId != null) {
            List<Long> runIds = jobRunMapper.selectList(new LambdaQueryWrapper<JobRun>()
                            .eq(JobRun::getJobId, jobId))
                    .stream()
                    .map(JobRun::getId)
                    .toList();
            if (runIds.isEmpty()) {
                return pageOf(0L, List.of());
            }
            wrapper.in(MessageStep::getRunId, runIds);
        }
        if (runId != null) {
            wrapper.eq(MessageStep::getRunId, runId);
        }
        if (messageId != null && !messageId.isBlank()) {
            wrapper.eq(MessageStep::getMessageId, messageId.trim());
        }
        if (businessKey != null && !businessKey.isBlank()) {
            wrapper.like(MessageStep::getBusinessKey, businessKey.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(MessageStep::getStatus, status.trim());
        }
        Page<MessageStep> result = messageStepMapper.selectPage(new Page<>(page, size), wrapper);
        return pageOf(result.getTotal(), result.getRecords());
    }

    public JobRun getRun(Long id) {
        JobRun run = jobRunMapper.selectById(id);
        if (run == null) {
            throw new NoSuchElementException("运行不存在: " + id);
        }
        return run;
    }

    public MessageStep getStep(Long id) {
        MessageStep step = messageStepMapper.selectById(id);
        if (step == null) {
            throw new NoSuchElementException("消息步不存在: " + id);
        }
        return step;
    }

    /** 从一条失败消息步精确断点续跑：用该步数据从该动作重放，成功后原步标记 REDELIVERED。 */
    public MessageStep redeliver(Long stepId) {
        MessageStep step = getStep(stepId);
        if (!FAILED.equals(step.getStatus())) {
            throw new IllegalStateException("仅失败消息步可重投: " + stepId);
        }
        JobRun run = jobRunMapper.selectById(step.getRunId());
        if (run == null) {
            throw new IllegalStateException("消息步所属运行不存在: " + step.getRunId());
        }
        Job job = jobMapper.selectById(run.getJobId());
        if (job == null || job.getStatus() == null || job.getStatus() != 1) {
            throw BusinessException.of(BusinessCode.JOB_NOT_ENABLED, "任务未启用，无法重投");
        }
        Object input = parseJson(step.getInputSnapshot());
        try (ProducerTemplate producer = camelContext.createProducerTemplate()) {
            producer.request("direct:job-" + job.getId() + "-resume", ex -> {
                ex.getMessage().setBody(input);
                ex.setProperty(RESUME_FROM, step.getStep());
                // 断点续跑沿用原业务键，重投后的消息步仍按同一业务实体分组/检索
                ex.setProperty(BUSINESS_KEY, step.getBusinessKey());
            });
        } catch (Exception e) {
            throw new IllegalStateException("重投失败: " + rootMessage(e), e);
        }
        step.setStatus(REDELIVERED);
        messageStepMapper.updateById(step);
        return step;
    }

    // ---------- 工具 ----------

    /** 从消息数据提取业务键：支持点分 JSON 路径与单段列名。 */
    public static String extractBusinessKey(Object data, String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        Object value = data;
        for (String part : businessKey.split("\\.")) {
            if (value instanceof Map<?, ?> map) {
                value = map.get(part);
            } else {
                value = null;
                break;
            }
        }
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> pageOf(long total, List<?> records) {
        return Map.of("total", total, "records", records);
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("消息步输入不是 JSON 对象，无法重投", e);
        }
    }

    private String toJson(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof String s) {
            return s;
        }
        if (input instanceof StreamCache cache) {
            try {
                cache.reset();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                cache.writeTo(out);
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                return String.valueOf(input);
            }
        }
        if (input instanceof InputStream in) {
            try (InputStream stream = in) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return String.valueOf(input);
            }
        }
        if (input instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? throwable.getMessage() : message;
    }
}
