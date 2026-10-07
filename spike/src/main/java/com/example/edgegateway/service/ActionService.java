package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.config.action.ActionConfig;
import com.example.edgegateway.config.action.FieldMappingConfig;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.ActionRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动作业务：绑定/更新/删除/列表，校验动作序号唯一、动作种类合法与链约束
 * （MQ_CONSUME 只能链首、最多一个 MQ_CONSUME + 一个带水位 READ_SQL、sourceStep/bodySourceStep
 * 引用必须指向链中更早的动作），
 * 引用数据源的动作校验数据源存在；动作变更后经 {@link RouteLifecycle} 自动重建该任务的
 * Camel 路由与调度（否则已运行路由不感知配置变化）。
 */
@Service
public class ActionService {

    private final ActionMapper actionMapper;
    private final JobMapper jobMapper;
    private final DataSourceMapper dataSourceMapper;
    private final MappingHistoryService mappingHistoryService;
    private final MappingSampleService mappingSampleService;
    private final RouteLifecycle routeLifecycle;

    public ActionService(
            ActionMapper actionMapper,
            JobMapper jobMapper,
            DataSourceMapper dataSourceMapper,
            MappingHistoryService mappingHistoryService,
            MappingSampleService mappingSampleService,
            RouteLifecycle routeLifecycle) {
        this.actionMapper = actionMapper;
        this.jobMapper = jobMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.mappingHistoryService = mappingHistoryService;
        this.mappingSampleService = mappingSampleService;
        this.routeLifecycle = routeLifecycle;
    }

    public Action add(Long jobId, ActionRequest request) {
        if (jobMapper.selectById(jobId) == null) {
            throw new NoSuchElementException("任务不存在: " + jobId);
        }
        validate(request);
        request.addition().validate();
        validateChain(jobId, request, null);
        if (indexExists(jobId, request.index(), null)) {
            throw new IllegalArgumentException("同任务下动作序号不能重复: " + request.index());
        }
        checkDataSourceReference(request.addition());
        Action action = toAction(jobId, request);
        actionMapper.insert(action);
        recordMappingHistory(jobId, request);
        rebuildRoutesIfEnabled(jobId);
        return action;
    }

    public Action update(Long id, ActionRequest request) {
        Action action = get(id);
        validate(request);
        request.addition().validate();
        validateChain(action.getJobId(), request, action.getId());
        if (indexExists(action.getJobId(), request.index(), id)) {
            throw new IllegalArgumentException("同任务下动作序号不能重复: " + request.index());
        }
        checkDataSourceReference(request.addition());
        apply(action, request);
        actionMapper.updateById(action);
        recordMappingHistory(action.getJobId(), request);
        rebuildRoutesIfEnabled(action.getJobId());
        return action;
    }

    public void delete(Long id) {
        Action action = get(id);
        actionMapper.deleteById(id);
        // 删动作时顺手把它的样例行带走（#119）：目标那格按动作 id，来源那格按这个动作的序号——
        // 那一步没了，它当来源的样例也就没主了（不留孤儿行）
        mappingSampleService.clearOfAction(action.getJobId(), action.getId(), action.getActionIndex());
        rebuildRoutesIfEnabled(action.getJobId());
    }

    /** 动作变更后自动生效：交由 {@link RouteLifecycle} 按启用状态决定是否重建路由与调度。 */
    private void rebuildRoutesIfEnabled(Long jobId) {
        routeLifecycle.rebuildIfEnabled(jobId);
    }

    public Action get(Long id) {
        Action action = actionMapper.selectById(id);
        if (action == null) {
            throw new NoSuchElementException("动作不存在: " + id);
        }
        return action;
    }

    public List<Action> list(Long jobId) {
        return actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .orderByAsc(Action::getActionIndex));
    }

    private boolean indexExists(Long jobId, Integer index, Long excludeId) {
        LambdaQueryWrapper<Action> wrapper = new LambdaQueryWrapper<Action>()
                .eq(Action::getJobId, jobId)
                .eq(Action::getActionIndex, index);
        if (excludeId != null) {
            wrapper.ne(Action::getId, excludeId);
        }
        return actionMapper.selectCount(wrapper) > 0;
    }

    private void validate(ActionRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("动作名称不能为空");
        }
        if (request.type() == null) {
            throw new IllegalArgumentException("动作类型不能为空");
        }
        if (request.index() == null) {
            throw new IllegalArgumentException("动作序号不能为空");
        }
    }

    private void checkDataSourceReference(ActionConfig config) {
        Object rawId = config.toMap().get("dataSourceId");
        if (rawId != null) {
            Long id = Long.valueOf(rawId.toString());
            if (dataSourceMapper.selectById(id) == null) {
                throw new IllegalArgumentException("引用的数据源不存在: " + id);
            }
        }
    }

    private void apply(Action action, ActionRequest request) {
        action.setName(request.name());
        action.setType(request.type());
        action.setActionIndex(request.index());
        action.setAddition(request.addition().toMap());
    }

    /** 请求 → 待落库动作（未 insert）；写时校验用它拼出"候选链"。 */
    private Action toAction(Long jobId, ActionRequest request) {
        Action action = new Action();
        action.setJobId(jobId);
        apply(action, request);
        return action;
    }

    /** 字段映射动作保存/更新后，把最终确认的映射规则沉淀进映射历史。 */
    private void recordMappingHistory(Long jobId, ActionRequest request) {
        if (request.type() == ActionKind.FIELD_MAP) {
            mappingHistoryService.recordFromAction(
                    jobId, ((FieldMappingConfig) request.addition()).rules());
        }
    }

    /**
     * 链约束（DS-8 MVP）：MQ_CONSUME 只能链首；最多一个 MQ_CONSUME + 一个带水位 READ_SQL；
     * MQ_PUBLISH 的 bodySourceStep 与 FIELD_MAP 规则的 sourceStep 必须指向链中存在的、更早的动作（#89 T1）。
     */
    private void validateChain(Long jobId, ActionRequest request, Long excludeId) {
        // 本次编辑之外的现存动作；三种引用校验共用，避免重复查库
        List<Action> existing = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                        .eq(Action::getJobId, jobId))
                .stream()
                .filter(a -> excludeId == null || !a.getId().equals(excludeId))
                .toList();
        if (request.type() == ActionKind.MQ_CONSUME && request.index() != null && request.index() != 1) {
            throw new IllegalArgumentException("MQ_CONSUME 只能作为链首（序号 1）");
        }
        if (request.type() == ActionKind.MQ_PUBLISH) {
            checkBodySourceStep(request, existing);
        }
        // 跨步骤引用写时就拦：字段映射的 rules.sourceStep 与一分多（拆分/还原两档）的 sourceStep
        // 走同一份链一致性校验（#89 / #140 / #142），报 400 而不是等到跑起来才「取不到数据」
        if (request.type() == ActionKind.FIELD_MAP || request.type() == ActionKind.ONE_TO_MANY) {
            checkCrossStepReferences(jobId, request, existing);
        }
        // 包含当前请求：新增时 +1，更新时按自身种类替换
        long mqConsume = existing.stream().filter(a -> a.getType() == ActionKind.MQ_CONSUME).count();
        long current = request.type() == ActionKind.MQ_CONSUME ? 1 : 0;
        if (mqConsume + current > 1) {
            throw new IllegalArgumentException("一条任务最多一个 MQ_CONSUME 动作");
        }
        boolean hasWatermarkRead = existing.stream()
                .map(a -> a.getType() == ActionKind.READ_SQL
                        ? (Map<String, Object>) a.getAddition()
                        : Map.<String, Object>of())
                .anyMatch(m -> m.containsKey("watermarkColumn"));
        boolean currentWatermark = request.type() == ActionKind.READ_SQL
                && request.addition().toMap().containsKey("watermarkColumn");
        if (hasWatermarkRead && currentWatermark) {
            throw new IllegalArgumentException("一条任务最多一个带水位的 READ_SQL 动作");
        }
    }

    /**
     * {@code bodySourceStep} 必填且必须指向链中存在的、序号更早的动作（显式声明"发哪一步的输出"，防静默发错）。
     *
     * <p>判据与另外两处共用 {@link ChainConsistencyValidator#stepProblem}：三个消费方问的是同一个问题
     * 「这一步能不能指向那一步」。</p>
     */
    private void checkBodySourceStep(ActionRequest request, List<Action> existing) {
        Object rawStep = request.addition().toMap().get("bodySourceStep");
        int bodyStep = rawStep == null ? 0 : Integer.parseInt(rawStep.toString());
        Set<Integer> indexes = existing.stream().map(Action::getActionIndex).collect(Collectors.toSet());
        if (request.index() != null) {
            indexes.add(request.index());
        }
        // 任务输入（0）不在动作表里，但恒可引用
        indexes.add(0);
        String problem = ChainConsistencyValidator.stepProblem("bodySourceStep", bodyStep, request.index(), indexes);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    /** FIELD_MAP 规则的 sourceStep / 拆分的 sourceStep 必须指向链中存在的、序号更早的动作（#89 / #140）。 */
    private void checkCrossStepReferences(Long jobId, ActionRequest request, List<Action> existing) {
        List<Action> candidate = new ArrayList<>(existing);
        candidate.add(toAction(jobId, request));
        List<String> problems = ChainConsistencyValidator.check(candidate);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(ChainConsistencyValidator.describeAll(problems));
        }
    }
}
