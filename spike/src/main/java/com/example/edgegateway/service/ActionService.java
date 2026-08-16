package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
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

    public ActionService(ActionMapper actionMapper, JobMapper jobMapper, DataSourceMapper dataSourceMapper) {
        this.actionMapper = actionMapper;
        this.jobMapper = jobMapper;
        this.dataSourceMapper = dataSourceMapper;
    }

    public Action add(Long jobId, ActionRequest request) {
        if (jobMapper.selectById(jobId) == null) {
            throw new NoSuchElementException("任务不存在: " + jobId);
        }
        validate(request);
        if (indexExists(jobId, request.index(), null)) {
            throw new IllegalArgumentException("同任务下动作序号不能重复: " + request.index());
        }
        checkDataSourceReference(request.addition());
        Action action = new Action();
        action.setJobId(jobId);
        apply(action, request);
        actionMapper.insert(action);
        return action;
    }

    public Action update(Long id, ActionRequest request) {
        Action action = get(id);
        validate(request);
        if (indexExists(action.getJobId(), request.index(), id)) {
            throw new IllegalArgumentException("同任务下动作序号不能重复: " + request.index());
        }
        checkDataSourceReference(request.addition());
        apply(action, request);
        actionMapper.updateById(action);
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

    private void checkDataSourceReference(String addition) {
        Map<String, Object> map = AdditionJson.parse(addition);
        Object id = map.get("dataSourceId");
        if (id != null && !id.toString().isBlank()) {
            Long dataSourceId = Long.valueOf(id.toString());
            if (dataSourceMapper.selectById(dataSourceId) == null) {
                throw new IllegalArgumentException("引用的数据源不存在: " + dataSourceId);
            }
        }
    }

    private void apply(Action action, ActionRequest request) {
        action.setName(request.name());
        action.setType(request.type());
        action.setActionIndex(request.index());
        action.setAddition(request.addition());
    }
}
