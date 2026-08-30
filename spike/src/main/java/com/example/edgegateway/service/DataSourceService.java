package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.config.ActionConfig;
import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.DataSourceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 数据源业务：负责配置校验（必填项、类型相关字段、JSON 合法性）、
 * CRUD 与"同站点重名"冲突检测。数据源变更（改/删）销毁对应连接池并让引用它的
 * 启用任务重建/停用路由（#68），避免运行中路由仍指向旧库/旧池。
 * 校验失败抛 IllegalArgumentException（400），
 * 重名抛 BusinessException(DUPLICATE_NAME)（409），不存在抛 NoSuchElementException（404）。
 */
@Service
public class DataSourceService {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceService.class);

    private final DataSourceMapper dataSourceMapper;
    private final DataSourceManager dataSourceManager;
    private final ActionMapper actionMapper;
    private final JobMapper jobMapper;
    private final RouteRegistry routeRegistry;

    public DataSourceService(
            DataSourceMapper dataSourceMapper,
            DataSourceManager dataSourceManager,
            ActionMapper actionMapper,
            JobMapper jobMapper,
            RouteRegistry routeRegistry) {
        this.dataSourceMapper = dataSourceMapper;
        this.dataSourceManager = dataSourceManager;
        this.actionMapper = actionMapper;
        this.jobMapper = jobMapper;
        this.routeRegistry = routeRegistry;
    }

    public DataSource create(DataSourceRequest request) {
        validate(request);
        // 同一站点下数据源名唯一，作为业务键
        if (exists(request.siteId(), request.name(), null)) {
            throw BusinessException.of(BusinessCode.DUPLICATE_NAME, "数据源名称已存在: " + request.name());
        }
        DataSource dataSource = toEntity(request);
        dataSourceMapper.insert(dataSource);
        return dataSource;
    }

    public DataSource update(Long id, DataSourceRequest request) {
        validate(request);
        DataSource existing = get(id);
        if (exists(request.siteId(), request.name(), id)) {
            throw BusinessException.of(BusinessCode.DUPLICATE_NAME, "数据源名称已存在: " + request.name());
        }
        existing.setSiteId(request.siteId());
        existing.setName(request.name());
        existing.setType(request.type());
        existing.setAddress(request.address());
        existing.setAddition(request.addition().toMap());
        dataSourceMapper.updateById(existing);
        // 连接参数可能已变化，旧池失效；路由级联动（#68）后续再做
        dataSourceManager.invalidate(id);
        rebuildRoutesUsingDataSource(id);
        return existing;
    }

    public void delete(Long id) {
        get(id);
        dataSourceMapper.deleteById(id);
        dataSourceManager.invalidate(id);
        disableRoutesUsingDataSource(id);
    }

    public DataSource get(Long id) {
        DataSource dataSource = dataSourceMapper.selectById(id);
        if (dataSource == null) {
            throw new NoSuchElementException("数据源不存在: " + id);
        }
        return dataSource;
    }

    public List<DataSource> list(String name, DataSourceType type) {
        LambdaQueryWrapper<DataSource> wrapper = new LambdaQueryWrapper<>();
        if (name != null && !name.isBlank()) {
            wrapper.like(DataSource::getName, name);
        }
        if (type != null) {
            wrapper.eq(DataSource::getType, type);
        }
        return dataSourceMapper.selectList(wrapper);
    }

    private boolean exists(Long siteId, String name, Long excludeId) {
        // 更新时排除自身，避免"改个字段就撞上自己"的误判
        LambdaQueryWrapper<DataSource> wrapper = new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getSiteId, siteId)
                .eq(DataSource::getName, name);
        if (excludeId != null) {
            wrapper.ne(DataSource::getId, excludeId);
        }
        return dataSourceMapper.selectCount(wrapper) > 0;
    }

    private DataSource toEntity(DataSourceRequest request) {
        DataSource dataSource = new DataSource();
        dataSource.setSiteId(request.siteId());
        dataSource.setName(request.name());
        dataSource.setType(request.type());
        dataSource.setAddress(request.address());
        dataSource.setAddition(request.addition().toMap());
        return dataSource;
    }

    private void validate(DataSourceRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("数据源名称不能为空");
        }
        if (request.siteId() == null) {
            throw new IllegalArgumentException("站点不能为空");
        }
        if (request.type() == null) {
            throw new IllegalArgumentException("不支持的数据源类型");
        }
        if (request.address() == null || request.address().isBlank()) {
            throw new IllegalArgumentException("数据源地址不能为空");
        }
        // 按类型校验 addition：交给类型化配置各自的 validate()
        DataSourceConfig config = request.addition();
        if (config == null) {
            throw new IllegalArgumentException("addition 不能为空");
        }
        config.validate();
    }

    /** #68：找出所有引用指定数据源的动作所在的任务 id。 */
    private Set<Long> findJobIdsUsingDataSource(Long dataSourceId) {
        List<Action> actions = actionMapper.selectList(new LambdaQueryWrapper<Action>()
                .ne(Action::getType, ActionKind.FIELD_MAP));
        Set<Long> jobIds = new LinkedHashSet<>();
        for (Action action : actions) {
            try {
                Object rawId = ActionConfig.from(action.getType(), addition(action)).toMap().get("dataSourceId");
                if (rawId != null && dataSourceId.equals(Long.valueOf(rawId.toString()))) {
                    jobIds.add(action.getJobId());
                }
            } catch (RuntimeException e) {
                // 个别动作配置解析失败不影响联动
                LOG.debug("解析动作数据源引用失败，跳过 action={}: {}", action.getId(), e.getMessage());
            }
        }
        return jobIds;
    }

    /** #68：数据源变更后，引用它的启用任务重建路由；重建失败的任务自动停用并汇总报错。 */
    private void rebuildRoutesUsingDataSource(Long dataSourceId) {
        List<String> failures = new ArrayList<>();
        for (Long jobId : findJobIdsUsingDataSource(dataSourceId)) {
            Job job = jobMapper.selectById(jobId);
            if (job == null || job.getStatus() == null || job.getStatus() != 1) {
                continue;
            }
            try {
                routeRegistry.enable(jobId);
            } catch (RuntimeException e) {
                job.setStatus(0);
                jobMapper.updateById(job);
                LOG.warn("数据源变更导致任务路由重建失败，已自动停用 job={}: {}", jobId, e.getMessage());
                failures.add("任务 " + jobId + "：" + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException("数据源已更新，但以下启用任务路由重建失败，已自动停用: "
                    + String.join("；", failures));
        }
    }

    /** #68：数据源删除后，引用它的启用任务移除路由并停用（避免悬空指向已删库）。 */
    private void disableRoutesUsingDataSource(Long dataSourceId) {
        for (Long jobId : findJobIdsUsingDataSource(dataSourceId)) {
            Job job = jobMapper.selectById(jobId);
            if (job == null || job.getStatus() == null || job.getStatus() != 1) {
                continue;
            }
            try {
                routeRegistry.disable(jobId);
            } catch (RuntimeException e) {
                LOG.warn("数据源删除后移除任务路由失败 job={}: {}", jobId, e.getMessage());
            }
            job.setStatus(0);
            jobMapper.updateById(job);
            LOG.info("数据源已删除，引用它的启用任务已自动停用 job={}", jobId);
        }
    }

    private Map<String, Object> addition(Action action) {
        return action.getAddition() == null ? Map.of() : action.getAddition();
    }
}
