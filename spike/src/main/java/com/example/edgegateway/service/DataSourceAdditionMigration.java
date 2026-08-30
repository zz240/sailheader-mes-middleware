package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0007 连接级收紧的存量迁移：把已持久化数据源 {@code addition} 里不再属于连接级的键移掉——
 * RABBITMQ 的 {@code queue}/{@code exchange}/{@code routingKey}（归动作）与 MYSQL 的 {@code url}（取 address）。
 * 幂等，仅更新含旧键的行；连接级字段（host/port/username/password）保留。
 */
@Component
public class DataSourceAdditionMigration {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceAdditionMigration.class);

    private final DataSourceMapper dataSourceMapper;

    public DataSourceAdditionMigration(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void stripLegacyKeys() {
        List<DataSource> rows = dataSourceMapper.selectList(new LambdaQueryWrapper<>());
        for (DataSource ds : rows) {
            Map<String, Object> addition = ds.getAddition();
            if (addition == null) {
                continue;
            }
            Map<String, Object> clean = new LinkedHashMap<>(addition);
            boolean changed = false;
            if (ds.getType() == DataSourceType.RABBITMQ) {
                for (String key : new String[]{"queue", "exchange", "routingKey"}) {
                    if (clean.remove(key) != null) {
                        changed = true;
                    }
                }
            } else if (ds.getType() == DataSourceType.MYSQL) {
                if (clean.remove("url") != null) {
                    changed = true;
                }
            }
            if (changed) {
                ds.setAddition(clean);
                dataSourceMapper.updateById(ds);
                LOG.info("Migrated data source {}: stripped legacy addition keys", ds.getId());
            }
        }
    }
}
