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
import com.example.edgegateway.model.MappingSampleKind;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.web.dto.FieldMappingEditorView;
import com.example.edgegateway.web.dto.MappingSampleRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 字段映射编辑器（新版）的服务端支持（#99）：给编辑器准备「目标字段清单」和「来源字段清单」，
 * 外加**粘进来的那份样例的落库**（#119）。
 *
 * <ul>
 *   <li>来源 = 链上更早的那个动作最近一次成功运行的输出（消息步快照），用结构探测展开成字段清单；
 *       或者编辑器里粘进来的一份样例 JSON（#115），两条路**共用同一份样例展开**，推出来的形状一致；</li>
 *   <li>目标 = 本动作之后最近的 WRITE_UPSERT 动作写的表，连库探测它的列（H3：只有写库动作能探测）；
 *       也可以粘一份目标数据（#118）；</li>
 *   <li>打开时（{@link #context}）**先看库里存过的那份**（#119）：这一步 / 这个写目标动作粘过一次并
 *       保存过 → 用存的那份（响应里带着原文）；没存过 → 上面那两条探测路，与 #119 之前一字不差；</li>
 *   <li>点保存时（{@link #saveSamples}）手里是粘的 → 存下来；手里是探测来的 → 把库里那一格删掉。</li>
 * </ul>
 *
 * <p>除了 {@code mapping_sample} 这一张表，它不写任何东西：**配置格式一个字不改**（样例不进
 * {@code action.addition}，见 #119「存哪」），探测那份也不落库。</p>
 */
@Service
public class FieldMappingEditorService {

    /** 链上没有更早的动作时，来源那一格算在「任务输入」这一步上（与界面顶部那个下拉同一口径）。 */
    private static final int TASK_INPUT_STEP = 0;

    /**
     * 「这份样例是库里存过的那份」这个标记（#119）。与 #115 的 {@code paste} 分开：界面要说清手里这份是
     * 「粘的（已存）」还是这一次刚粘的，而保存那一刻两者都算「手里是粘的」。
     */
    static final String STORED_SAMPLE_KEY = "stored";

    /** 粘的那条路（这一次粘进来的）在样例上留下的记号，见 #115。 */
    private static final String PASTED_SAMPLE_KEY = "paste";

    private final ActionMapper actionMapper;
    private final JobRunMapper jobRunMapper;
    private final MessageStepMapper messageStepMapper;
    private final StructureProbeService probeService;
    private final DataSourceService dataSourceService;
    private final MappingSampleService sampleService;
    private final ObjectMapper objectMapper;

    public FieldMappingEditorService(
            ActionMapper actionMapper,
            JobRunMapper jobRunMapper,
            MessageStepMapper messageStepMapper,
            StructureProbeService probeService,
            DataSourceService dataSourceService,
            MappingSampleService sampleService,
            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.jobRunMapper = jobRunMapper;
        this.messageStepMapper = messageStepMapper;
        this.probeService = probeService;
        this.dataSourceService = dataSourceService;
        this.sampleService = sampleService;
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
                sourceOf(chain, jobId, actionId, index),
                storedTargetSample(jobId, writeAction),
                labels(chain));
    }

    /**
     * 点「保存」时把手里那两份样例落库（#119 规则 2）。
     *
     * <p>两边各自：带原文 = 手里是粘的 → 存下来（同一格覆盖）；不带原文 = 手里是探测来的 → 把那一格删掉。
     * 整个一边没给 = 跟这次保存无关，库里不动。归属键与「存过的那份怎么读回来」是一对，见
     * {@link MappingSampleService}。</p>
     *
     * <p>归属要在**这一次请求给的 jobId 之内**对得上（不然写下一行谁都不会去读的样例，或清掉别的任务/动作
     * 那一格）：来源那一步必须是这个任务链上的某一步（0 = 任务输入），目标必须是这个任务下的动作。</p>
     */
    public void saveSamples(Long jobId, MappingSampleRequest request) {
        MappingSampleRequest.SourceSampleSave source = request.source();
        if (source != null) {
            sampleService.save(jobId, MappingSampleKind.SOURCE, stepOf(chainOf(jobId), source.step()),
                    source.json());
        }
        MappingSampleRequest.TargetSampleSave target = request.target();
        if (target != null) {
            sampleService.save(jobId, MappingSampleKind.TARGET, writeActionIdOf(jobId, target.actionId()),
                    target.json());
        }
    }

    /** 来源那一格归到哪一步：与 {@link #sourceFieldsFromSample} 同一条口径（不能空着，且得在链上）。 */
    private Long stepOf(List<Action> chain, Integer step) {
        if (step == null) {
            throw new IllegalArgumentException("这份样例要归到某一个来源（顶部那个「默认来源」），不能空着");
        }
        boolean onChain = step == TASK_INPUT_STEP
                || chain.stream().anyMatch(a -> step.equals(a.getActionIndex()));
        if (!onChain) {
            throw new IllegalArgumentException("这份样例要归到链上的某一步，任务里没有序号 " + step + " 的动作");
        }
        return step.longValue();
    }

    /** 目标那一格归到哪个写目标动作：没有它就没有归属键；它还得是这个任务下的动作。 */
    private Long writeActionIdOf(Long jobId, Long actionId) {
        if (actionId == null) {
            throw new IllegalArgumentException("目标那份样例要归到某一个写目标动作，不能空着");
        }
        Action action = actionMapper.selectById(actionId);
        if (action == null || !jobId.equals(action.getJobId())) {
            throw new IllegalArgumentException("目标那份样例要归到这个任务下的写目标动作，找不到: " + actionId);
        }
        return actionId;
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
     * <p>`step` 必填——来源为空会让基于样例的那几条检查静默全部不判。</p>
     */
    public FieldMappingEditorView.SourceSample sourceFieldsFromSample(Long jobId, Integer step, String json) {
        if (step == null) {
            throw new IllegalArgumentException("这份样例要归到某一个来源（顶部那个「默认来源」），不能空着");
        }
        ParsedSample sample = parseSample(json);
        return new FieldMappingEditorView.SourceSample(
                step, stepNameOf(chainOf(jobId), step), PASTED_SAMPLE_KEY, sample.sampledFirstItem(),
                probeService.probeSample(sample.value()).fields(), json);
    }

    /**
     * 编辑器里粘进来的一份样例 JSON → **目标**字段清单（#118）。
     *
     * <p>与连库探测那条路并列（{@code POST /api/actions/{id}/probe-target} 连库读列，这个用请求体里那份
     * 样例），推断是同一份（{@link #parseSample} + {@code probeSample}）——所以粘出来的字段形状与探出来的
     * 一致，编辑器那一侧照样走 `targetFieldsFromPaths`，数组会折成一行数组映射加相对路径的子行。</p>
     *
     * <p>**必填推不出来**：NOT NULL 只有连库探测知道，一份 JSON 里看不出来（某条里是 null 只说明那一条里它
     * 是空的），所以这里一律按不必填算，界面上写明这一点（#118 验收四）。</p>
     */
    public FieldMappingEditorView.TargetSample targetFieldsFromSample(String json) {
        ParsedSample sample = parseSample(json);
        return new FieldMappingEditorView.TargetSample(PASTED_SAMPLE_KEY,
                sample.sampledFirstItem(), probeService.probeSample(sample.value()).fields(), json);
    }

    // ---------- 内部 ----------

    /** 一份样例：解析并定好「推哪一条」之后的样子。 */
    private record ParsedSample(Object value, boolean sampledFirstItem, String raw) {

        /** 共用的解析结果（{@link MappingSampleData.Parsed}）+ 编辑器手里那段原文。 */
        static ParsedSample of(MappingSampleData.Parsed parsed, String raw) {
            return new ParsedSample(parsed.value(), parsed.sampledFirstItem(), raw);
        }
    }

    /**
     * 存下来的那段文本 → 样例；解析不出来（或压根没存）时当**没存过**，退回探测那条路。
     *
     * <p>不往外抛：库里的一行坏数据不该让整个编辑器打不开。也不顺手把那行删掉——它可能是别人手上唯一
     * 那份东西，用户再点一次保存就会覆盖它。</p>
     */
    private ParsedSample tryParseSample(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return parseSample(json);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 粘进来的那段文本 → 样例数据（#115 / #118，来源与目标两条粘贴路共用这一份）。
     *
     * <p>解析口径（对象原样、数组按第 1 条、那几句说法）全在 {@link MappingSampleData#parse} 里：
     * 字段清单是那份数据的纯函数，试算（#116）跑的也必须是那份数据，所以两处共用一份实现。</p>
     *
     * <p>原文（`raw`）一起带着：涂到界面上、再点保存时要原样写回库里（#119「存什么 = 原文」）；
     * 试算也要把原文发回去跑（#116）。</p>
     */
    private ParsedSample parseSample(String json) {
        return ParsedSample.of(MappingSampleData.parse(objectMapper, json), json);
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

    /**
     * 打开时的来源那份（#119 规则 1）：**这一步存过粘的 → 用存的那份**；没存过 → 探测那条路，与今天
     * 一字不差。
     *
     * <p>「链上还没有更早的动作」也算「任务输入（0）」这一格——冷启动与 MQ 打头的任务本来就探不出来源
     * 清单（那正是 #115 / #118 存在的理由），存过的那份样例必须能在重开时回来。**只有存过**才把这一格
     * 变成非空：没存过时返回 null，与 #119 之前一样（那时链上还没有更早的动作 → 左边是空的）。</p>
     */
    private FieldMappingEditorView.SourceSample sourceOf(List<Action> chain, Long jobId, Long actionId, Integer index) {
        Action previous = previousActionOf(chain, actionId, index);
        Integer step = previous == null ? TASK_INPUT_STEP : previous.getActionIndex();
        FieldMappingEditorView.SourceSample stored = storedSourceSample(jobId, chain, step);
        if (stored != null) {
            return stored;
        }
        return previous == null ? null : latestOutput(chain, jobId, previous.getActionIndex()).sample();
    }

    /** 链上更早那个动作（编辑当前动作时不把它自己算进去）；没有就是 null。 */
    private Action previousActionOf(List<Action> chain, Long actionId, Integer index) {
        return chain.stream()
                .filter(a -> actionId == null || !a.getId().equals(actionId))
                .filter(a -> a.getActionIndex() != null && a.getActionIndex() < index)
                .max(Comparator.comparing(Action::getActionIndex))
                .orElse(null);
    }

    /**
     * 库里给这一步存过的那份粘的（#119）：解析原文、现推字段清单，`sourceKey` 记成 `stored`
     * （界面据此写明「粘的（已存）」），**原文一起带走**——保存时手里必须还握着它，才能原样写回库里。
     */
    private FieldMappingEditorView.SourceSample storedSourceSample(Long jobId, List<Action> chain, Integer step) {
        ParsedSample sample = tryParseSample(
                sampleService.stored(jobId, MappingSampleKind.SOURCE, step.longValue()));
        return sample == null ? null
                : new FieldMappingEditorView.SourceSample(step, stepNameOf(chain, step), STORED_SAMPLE_KEY,
                        sample.sampledFirstItem(), probeService.probeSample(sample.value()).fields(),
                        sample.raw());
    }

    /**
     * 库里给这个写目标动作存过的那份目标样例（#119）；没存过是 null，编辑器那一侧就走连库探测。
     *
     * <p>与来源那侧同一套：原文 + 现推的字段清单。必填照样推不出来（一份 JSON 里没有 NOT NULL），
     * 所以界面那行小字照旧写着（#118 验收四）。</p>
     */
    private FieldMappingEditorView.TargetSample storedTargetSample(Long jobId, Action writeAction) {
        if (writeAction == null) {
            return null;
        }
        ParsedSample sample = tryParseSample(
                sampleService.stored(jobId, MappingSampleKind.TARGET, writeAction.getId()));
        return sample == null ? null
                : new FieldMappingEditorView.TargetSample(STORED_SAMPLE_KEY, sample.sampledFirstItem(),
                        probeService.probeSample(sample.value()).fields(), sample.raw());
    }

    /** 某一步最近一次成功运行的数据快照 → 字段清单（连同那份快照的原文，试算要拿它跑）。 */
    private SourceOutput latestOutput(List<Action> chain, Long jobId, Integer step) {
        if (step == null) {
            return new SourceOutput(null, null, null, null, List.of());
        }
        String actionName = stepNameOf(chain, step);
        Object snapshot = latestSnapshot(jobId, step);
        if (snapshot == null) {
            return new SourceOutput(step, actionName, null, null, List.of());
        }
        SourceStructure structure = probeService.probeSample(snapshot);
        return new SourceOutput(step, actionName, structure.key(),
                MappingSampleData.write(objectMapper, snapshot), structure.fields());
    }

    private record SourceOutput(Integer step, String stepName, String sourceKey, String json,
                                List<SourceField> fields) {
        FieldMappingEditorView.SourceSample sample() {
            // 运行快照那条路不做「按第几条推」的说明（sampledFirstItem 为 false）：界面上那句话只说粘贴
            // 那条路，这条路的行为与 #115 之前一模一样。
            // 原文（json）跟着一起给：**探测那份仍然不落库**（#119），它只是编辑器手里那份数据的出处——
            // 试算（#116）拿它跑，与左边清单说的是同一份东西。
            return step == null ? null
                    : new FieldMappingEditorView.SourceSample(step, stepName, sourceKey, false, fields, json);
        }
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
