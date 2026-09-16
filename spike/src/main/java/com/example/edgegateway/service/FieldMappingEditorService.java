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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 字段映射编辑器（新版）的服务端支持（#99）：给编辑器准备「目标字段清单」和「来源字段清单」。
 *
 * <ul>
 *   <li>来源 = 链上更早的那个动作最近一次成功运行的输出（消息步快照），用结构探测展开成字段清单；
 *       或者编辑器里粘进来的一份样例 JSON（#115），两条路**共用同一份样例展开**，推出来的形状一致；</li>
 *   <li>目标 = 本动作之后最近的 WRITE_UPSERT 动作写的表，连库探测它的列（H3：只有写库动作能探测）。</li>
 * </ul>
 *
 * <p>不写数据库，也不改任何配置格式。</p>
 */
@Service
public class FieldMappingEditorService {

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
        List<Action> chain = chainOf(jobId);
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
                        dataSourceIdOf(writeAction),
                        tableNameOf(writeAction)),
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
        return latestOutput(chainOf(jobId), jobId, step).sample();
    }

    /**
     * 编辑器里粘进来的一份样例 JSON → 字段清单（#115）。
     *
     * <p>推断**复用探测那条路**那份样例展开（{@link StructureProbeService#probeSample}）：同一份数据
     * 从运行记录里来还是从剪贴板里来，推出来的字段完全一样（名称、类型、可空、样例值、数组分组）。
     * 这里只多做两件事：认一下这段文本是不是收得下，以及这份样例归到哪一步。</p>
     *
     * <p>收对象或数组；数组按第 1 条推（`sampledFirstItem` 把这件事告诉界面，界面上注明「按第 1 条推的」）。
     * 推的是**那一条自己**的路径：整份数组不能直接交给样例展开——那样出来的路径会带一个没有名字的
     * `[0].` 前缀（`[0].order_no`），编辑器认不出它属于哪个数组（`source-picker.ts` 的 `owningArrayOf`
     * 会得到空串）。`step` 必填——来源为空会让基于样例的那几条检查静默全部不判。</p>
     */
    public FieldMappingEditorView.SourceSample sourceFieldsFromSample(Long jobId, Integer step, String json) {
        if (step == null) {
            throw new IllegalArgumentException("这份样例要归到某一个来源（顶部那个「默认来源」），不能空着");
        }
        Object sample = parseSample(json);
        boolean sampledFirstItem = sample instanceof List<?>;
        if (sample instanceof List<?> list) {
            sample = list.get(0);
            if (!(sample instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("数组里第 1 条不是一个对象，推不出字段");
            }
        }
        return new FieldMappingEditorView.SourceSample(
                step, stepNameOf(chainOf(jobId), step), "paste", sampledFirstItem,
                probeService.probeSample(sample).fields());
    }

    // ---------- 内部 ----------

    /** 粘进来的那段文本 → 样例数据；解析不了或用不上时给出能照着改的说法。 */
    private Object parseSample(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("还没有粘 JSON");
        }
        Object sample;
        try {
            sample = objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("这段 JSON 解析不了: " + e.getOriginalMessage());
        }
        if (sample instanceof List<?> list && list.isEmpty()) {
            throw new IllegalArgumentException("这个数组里一条数据都没有，推不出字段");
        }
        if (!(sample instanceof Map<?, ?>) && !(sample instanceof List<?>)) {
            throw new IllegalArgumentException("收对象或数组：要一份来源数据的 JSON，例如 {\"order_no\":\"PO-1\"}");
        }
        return sample;
    }

    /** 任务的动作链，按执行顺序（同序号按 id，保证顺序稳定）。 */
    private List<Action> chainOf(Long jobId) {
        return actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .orderByAsc(Action::getActionIndex)
                .orderByAsc(Action::getId));
    }

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

    /** 写目标表名：编辑器拿它去探测目标结构（探测请求里可以覆盖）。 */
    private String tableNameOf(Action action) {
        Object value = action.getAddition() == null ? null : action.getAddition().get("tableName");
        return value == null ? null : value.toString();
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

    private record SourceOutput(Integer step, String stepName, String sourceKey, List<SourceField> fields) {
        FieldMappingEditorView.SourceSample sample() {
            // 运行快照那条路不做「按第几条推」的说明（sampledFirstItem 为 false）：界面上那句话只说粘贴
            // 那条路，这条路的行为与 #115 之前一模一样。
            return step == null ? null
                    : new FieldMappingEditorView.SourceSample(step, stepName, sourceKey, false, fields);
        }
    }

    /** 某一步最近一次成功运行的数据快照 → 字段清单。 */
    private SourceOutput latestOutput(List<Action> chain, Long jobId, Integer step) {
        if (step == null) {
            return new SourceOutput(null, null, null, List.of());
        }
        String actionName = stepNameOf(chain, step);
        Object snapshot = latestSnapshot(jobId, step);
        if (snapshot == null) {
            return new SourceOutput(step, actionName, null, List.of());
        }
        SourceStructure structure = probeService.probeSample(snapshot);
        return new SourceOutput(step, actionName, structure.key(), structure.fields());
    }

    /** 这个序号上那个动作叫什么名字（任务输入那一步没有动作，返回 null）。 */
    private String stepNameOf(List<Action> chain, Integer step) {
        if (step == null) {
            return null;
        }
        return chain.stream()
                .filter(a -> step.equals(a.getActionIndex()))
                .map(Action::getName)
                .findFirst()
                .orElse(null);
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
                .eq(MessageStep::getStatus, MessageHistoryService.SUCCESS)
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
