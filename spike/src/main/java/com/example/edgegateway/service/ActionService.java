package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.config.ActionConfig;
import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.RemoteAccessConfig;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionType;
import com.example.edgegateway.web.dto.ActionRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 动作业务：绑定/更新/删除/列表，校验动作序号在任务内唯一，
 * 并在远程访问动作引用 dataSourceId 时校验数据源存在。
 */
@Service
public class ActionService {

    private final ActionMapper actionMapper;
    private final JobMapper jobMapper;
    private final DataSourceMapper dataSourceMapper;
    private final MappingHistoryService mappingHistoryService;

    public ActionService(
            ActionMapper actionMapper,
            JobMapper jobMapper,
            DataSourceMapper dataSourceMapper,
            MappingHistoryService mappingHistoryService) {
        this.actionMapper = actionMapper;
        this.jobMapper = jobMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.mappingHistoryService = mappingHistoryService;
    }

    public Action add(Long jobId, ActionRequest request) {
        if (jobMapper.selectById(jobId) == null) {
            throw new NoSuchElementException("任务不存在: " + jobId);
        }
        validate(request);
        ActionConfig.from(request.type(), request.addition()).validate();
        if (indexExists(jobId, request.index(), null)) {
            throw new IllegalArgumentException("同任务下动作序号不能重复: " + request.index());
        }
        checkDataSourceReference(request.type(), request.addition());
        Action action = new Action();
        action.setJobId(jobId);
        apply(action, request);
        actionMapper.insert(action);
        recordMappingHistory(jobId, request);
        return action;
    }

    public Action update(Long id, ActionRequest request) {
        Action action = get(id);
        validate(request);
        ActionConfig.from(request.type(), request.addition()).validate();
        if (indexExists(action.getJobId(), request.index(), id)) {
            throw new IllegalArgumentException("同任务下动作序号不能重复: " + request.index());
        }
        checkDataSourceReference(request.type(), request.addition());
        apply(action, request);
        actionMapper.updateById(action);
        recordMappingHistory(action.getJobId(), request);
        return action;
    }

    public void delete(Long id) {
        get(id);
        actionMapper.deleteById(id);
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

    private void checkDataSourceReference(ActionType type, Map<String, Object> addition) {
        ActionConfig config = ActionConfig.from(type, addition);
        if (config instanceof RemoteAccessConfig remote && remote.dataSourceId() != null) {
            if (dataSourceMapper.selectById(remote.dataSourceId()) == null) {
                throw new IllegalArgumentException("引用的数据源不存在: " + remote.dataSourceId());
            }
        }
    }

    private void apply(Action action, ActionRequest request) {
        action.setName(request.name());
        action.setType(request.type());
        action.setActionIndex(request.index());
        action.setAddition(request.addition());
    }

    /** 字段映射动作保存/更新后，把最终确认的映射规则沉淀进映射历史。 */
    private void recordMappingHistory(Long jobId, ActionRequest request) {
        if (request.type() == ActionType.FIELD_MAPPING) {
            mappingHistoryService.recordFromAction(
                    jobId, FieldMappingConfig.from(request.addition()).rules());
        }
    }
}
