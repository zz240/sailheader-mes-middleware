package com.example.edgegateway.config.datasource;

import com.example.edgegateway.model.DataSourceType;

import java.util.Map;

/**
 * 数据源配置的类型化包装：每种数据源一个 record，校验逻辑写在各自类型里。
 * 请求边界由 DataSourceRequest 的 @JsonCreator 按 type 转强类型（EXTERNAL_PROPERTY 对 record 不可用），
 * 服务层 toMap() 落库（Map 存储不变），读取时 DataSourceConfig.from(type, map) 恢复强类型。
 */
public sealed interface DataSourceConfig
        permits HttpDataSourceConfig, JdbcDataSourceConfig, RabbitMqDataSourceConfig {

    DataSourceType type();

    /** 每种数据源自己校验必填字段，失败抛 IllegalArgumentException（映射为 400）。 */
    void validate();

    /** 转回 Map 供实体落库（不含 null 值）。 */
    Map<String, Object> toMap();

    static DataSourceConfig from(DataSourceType type, Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        return switch (type) {
            case HTTP -> HttpDataSourceConfig.from(map);
            case MYSQL -> MysqlDataSourceConfig.from(map);
            case SQLSERVER -> SqlServerDataSourceConfig.from(map);
            case RABBITMQ -> RabbitMqDataSourceConfig.from(map);
        };
    }

}
