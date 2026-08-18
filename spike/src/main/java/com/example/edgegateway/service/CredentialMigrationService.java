package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 存量明文凭证一次性迁移：遍历数据源，触发敏感字段类型处理器重新序列化
 * （写入时自动加密 password/token 等敏感键）。重复执行无害（已加密值跳过）。
 */
@Service
public class CredentialMigrationService {

    private final DataSourceMapper dataSourceMapper;

    public CredentialMigrationService(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    public List<String> migrateAll() {
        List<DataSource> dataSources = dataSourceMapper.selectList(new LambdaQueryWrapper<>());
        List<String> migrated = new ArrayList<>();
        for (DataSource dataSource : dataSources) {
            if (dataSource.getAddition() == null || dataSource.getAddition().isEmpty()) {
                continue;
            }
            dataSourceMapper.updateById(dataSource);
            migrated.add(dataSource.getName() + "(#" + dataSource.getId() + ")");
        }
        return migrated;
    }
}
