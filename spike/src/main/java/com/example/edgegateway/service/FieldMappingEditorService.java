package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.web.dto.FieldMappingEditorView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 字段映射编辑器（新版）的服务端支持（#99）：给编辑器准备「目标字段清单」和「来源字段清单」。
 *
 * <ul>
 *   <li>来源 = 链上更早的那个动作最近一次成功运行的输出（消息步快照），用结构探测展开成字段清单；</li>
 *   <li>目标 = 本动作之后最近的 WRITE_UPSERT 动作写的表，连库探测它的列（H3：只有写库动作能探测）。</li>
 * </ul>
 *
 * <p>不写数据库，也不改任何配置格式。</p>
 */
@Service
public class FieldMappingEditorService {

    private static final String SUCCESS = "SUCCESS";

    private final ActionMapper actionMapper;
    private final JobRunMapper jobRunMapper;
    private final MessageStepMapper messageStepMapper;
    private final StructureProbeService probeService;
    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper;

    public FieldMappingEditorService(
            ActionMapper actionMapper,
            JobRunMapper jobRunMapper,
            MessageStepMapper messageStepMapper,
            StructureProbeService probeService,
            DataSourceService dataSourceService,
            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.jobRunMapper = jobRunMapper;
        this.messageStepMapper = messageStepMapper;
        this.probeService = probeService;
        this.dataSourceService = dataSourceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 编辑器上下文。`actionId` / `actionIndex` 二选一：编辑已有动作时给 actionId，
     * 新建动作时给它准备占用的序号。
     */
    public FieldMappingEditorView context(Long jobId, Long actionId, Integer actionIndex) {
        List<Action> chain = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .orderByAsc(Action::getActionIndex)
                .orderByAsc(Action::getId));
        if (chain.isEmpty()) {
            throw new NoSuchElementException("任务下还没有动作: " + jobId);
        }
        Integer index = resolveIndex(chain, actionId, actionIndex);
        Action writeAction = writeTargetOf(chain, actionId, index);
        return new FieldMappingEditorView(
                jobId,
                writeAction == null ? null : new FieldMappingEditorView.WriteTarget(
                        writeAction.getId(),
                        writeAction.getActionIndex(),
                        writeAction.getName(),
                        dataSourceIdOf(writeAction)),
                sourceOf(chain, jobId, actionId, index).sample(),
                labels(chain));
    }

    /** 目标字段清单（列名/类型/是否必填），从写目标那张表探测。 */
    public SourceStructure probeTargetFields(Long actionId, String table, boolean refresh) {
        Action action = actionMapper.selectById(actionId);
        if (action == null) {
            throw new NoSuchElementException("动作不存在: " + actionId);
        }
        Long dataSourceId = dataSourceIdOf(action);
        if (dataSourceId == null) {
            throw new IllegalArgumentException("这个写动作没有选数据源，无法探测目标结构: " + actionId);
        }
        return probeService.probeFromDb(dataSourceService.get(dataSourceId), table, refresh);
    }

    /** 单独取一个来源步骤的字段清单（编辑器顶部切换默认来源时用）。 */
    public FieldMappingEditorView.SourceSample sourceFields(Long jobId, Integer step) {
        List<Action> chain = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .orderByAsc(Action::getActionIndex));
        return latestOutput(chain, jobId, step).sample();
    }

    // ---------- 内部 ----------

    private Integer resolveIndex(List<Action> chain, Long actionId, Integer actionIndex) {
        if (actionId != null) {
            return chain.stream()
                    .filter(a -> a.getId().equals(actionId))
                    .map(Action::getActionIndex)
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("动作不存在: " + actionId));
        }
        if (actionIndex != null) {
            return actionIndex;
        }
        return chain.get(chain.size() - 1).getActionIndex();
    }

    /** 写目标 = 本动作之后最近的 WRITE_UPSERT；编辑当前动作时从它之后找。 */
    private Action writeTargetOf(List<Action> chain, Long actionId, Integer index) {
        return chain.stream()
                .filter(a -> actionId == null || !a.getId().equals(actionId))
                .filter(a -> a.getActionIndex() != null && a.getActionIndex() > index)
                .filter(a -> a.getType() == ActionKind.WRITE_UPSERT)
                .min(Comparator.comparing(Action::getActionIndex))
                .orElse(null);
    }

    private Long dataSourceIdOf(Action action) {
        Object value = action.getAddition() == null ? null : action.getAddition().get("dataSourceId");
        return value == null ? null : Long.valueOf(value.toString());
    }

    /** 来源 = 链上更早那个动作最近一次成功运行的输出快照。 */
    private SourceOutput sourceOf(List<Action> chain, Long jobId, Long actionId, Integer index) {
        Action previous = chain.stream()
                .filter(a -> actionId == null || !a.getId().equals(actionId))
                .filter(a -> a.getActionIndex() != null && a.getActionIndex() < index)
                .max(Comparator.comparing(Action::getActionIndex))
                .orElse(null);
        if (previous == null) {
            return new SourceOutput(null, null, null, List.of());
        }
        return latestOutput(chain, jobId, previous.getActionIndex());
    }

    private record SourceOutput(Integer step, String stepName, String sourceKey,
                                List<FieldMappingEditorView.Field> fields) {
        FieldMappingEditorView.SourceSample sample() {
            return step == null ? null
                    : new FieldMappingEditorView.SourceSample(step, stepName, sourceKey, fields);
        }
    }

    /** 某一步最近一次成功运行的数据快照 → 字段清单。 */
    private SourceOutput latestOutput(List<Action> chain, Long jobId, Integer step) {
        if (step == null) {
            return new SourceOutput(null, null, null, List.of());
        }
        String actionName = chain.stream()
                .filter(a -> step.equals(a.getActionIndex()))
                .map(Action::getName)
                .findFirst()
                .orElse(null);
        Object snapshot = latestSnapshot(jobId, step);
        if (snapshot == null) {
            return new SourceOutput(step, actionName, null, List.of());
        }
        SourceStructure structure = probeService.probeSample(snapshot);
        List<FieldMappingEditorView.Field> fields = new ArrayList<>();
        for (SourceField field : structure.fields()) {
            fields.add(new FieldMappingEditorView.Field(
                    field.name(), field.type(), field.nullable(), field.samples()));
        }
        return new SourceOutput(step, actionName, structure.key(), fields);
    }

    private Object latestSnapshot(Long jobId, Integer step) {
        List<Long> runIds = jobRunMapper.selectList(new LambdaQueryWrapper<JobRun>()
                        .eq(JobRun::getJobId, jobId))
                .stream()
                .map(JobRun::getId)
                .toList();
        if (runIds.isEmpty()) {
            return null;
        }
        // 取最近一条成功的这一步快照；不要求整次运行成功——某一步的输出照样能当样例。
        List<MessageStep> steps = messageStepMapper.selectList(new LambdaQueryWrapper<MessageStep>()
                .in(MessageStep::getRunId, runIds)
                .eq(MessageStep::getStep, step)
                .eq(MessageStep::getStatus, SUCCESS)
                .orderByDesc(MessageStep::getId)
                .last("LIMIT 1"));
        if (steps.isEmpty()) {
            return null;
        }
        return parseSnapshot(steps.get(0).getDataSnapshot());
    }

    /** 快照列里存的是 JSON 文本；解析不出来就当没有样例（编辑器退化成手填）。 */
    private Object parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<FieldMappingEditorView.ActionLabel> labels(List<Action> chain) {
        List<FieldMappingEditorView.ActionLabel> labels = new ArrayList<>();
        for (Action action : chain) {
            labels.add(new FieldMappingEditorView.ActionLabel(
                    action.getActionIndex(),
                    action.getName(),
                    action.getType() == null ? null : action.getType().name()));
        }
        return labels;
    }
}
