package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.web.dto.DataSourceRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 数据源业务：负责配置校验（必填项、类型相关字段、JSON 合法性）、
 * CRUD 与"同站点重名"冲突检测。校验失败抛 IllegalArgumentException（400），
 * 重名抛 ConflictException（409），不存在抛 NoSuchElementException（404）。
 */
@Service
public class DataSourceService {

    private final DataSourceMapper dataSourceMapper;
    public DataSourceService(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    public DataSource create(DataSourceRequest request) {
        validate(request);
        // 同一站点下数据源名唯一，作为业务键
        if (exists(request.siteId(), request.name(), null)) {
            throw new ConflictException("数据源名称已存在: " + request.name());
        }
        DataSource dataSource = toEntity(request);
        dataSourceMapper.insert(dataSource);
        return dataSource;
    }

    public DataSource update(Long id, DataSourceRequest request) {
        validate(request);
        DataSource existing = get(id);
        if (exists(request.siteId(), request.name(), id)) {
            throw new ConflictException("数据源名称已存在: " + request.name());
        }
        existing.setSiteId(request.siteId());
        existing.setName(request.name());
        existing.setType(request.type());
        existing.setAddress(request.address());
        existing.setAddition(request.addition());
        dataSourceMapper.updateById(existing);
        return existing;
    }

    public void delete(Long id) {
        get(id);
        dataSourceMapper.deleteById(id);
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
        dataSource.setAddition(request.addition());
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
        // 按类型校验 addition 中的必填字段；addition 是请求体里的 JSON 对象
        Map<String, Object> addition = request.addition() == null ? Map.of() : request.addition();
        switch (request.type()) {
            case HTTP -> require(addition, "method", "HTTP 数据源需要 method");
            case MYSQL -> {
                require(addition, "url", "MySQL 数据源需要 url");
                require(addition, "username", "MySQL 数据源需要 username");
                require(addition, "password", "MySQL 数据源需要 password");
            }
            case RABBITMQ -> require(addition, "queue", "RabbitMQ 数据源需要 queue");
        }
    }

    private void require(Map<String, Object> addition, String key, String message) {
        Object value = addition.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
