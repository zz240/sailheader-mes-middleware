package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;

import java.util.Map;

/**
 * 数据源配置的类型化包装：每种数据源一个 record，校验逻辑写在各自类型里。
 * 实体/DTO 仍以 Map 存储（MySQL JSON 列），到服务边界时转成强类型。
 */
public sealed interface DataSourceConfig
        permits HttpDataSourceConfig, MysqlDataSourceConfig, RabbitMqDataSourceConfig {

    DataSourceType type();

    /** 每种数据源自己校验必填字段，失败抛 IllegalArgumentException（映射为 400）。 */
    void validate();

    static DataSourceConfig from(DataSourceType type, Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        return switch (type) {
            case HTTP -> HttpDataSourceConfig.from(map);
            case MYSQL -> MysqlDataSourceConfig.from(map);
            case RABBITMQ -> RabbitMqDataSourceConfig.from(map);
        };
    }

}
