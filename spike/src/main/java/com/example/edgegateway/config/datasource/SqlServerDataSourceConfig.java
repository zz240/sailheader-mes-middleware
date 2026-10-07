package com.example.edgegateway.config.datasource;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.DataSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SQL Server 数据源配置（连接级，ADR-0007）：连接 url 取数据源 {@code address}，addition 仅存 username/password。
 *
 * <p>字段与 {@link MysqlDataSourceConfig} 现在相同，但**不合并**（ADR-0014 决策 2）：
 * SQL Server 的连接属性（{@code encrypt}、{@code trustServerCertificate}、{@code instanceName}）
 * 迟早要加，到时用各自类型承载最省事，也不用让 MySQL 侧跟着长出一堆用不上的字段。</p>
 */
public record SqlServerDataSourceConfig(String username, String password)
        implements JdbcDataSourceConfig {

    public static SqlServerDataSourceConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("username", "password"));
        return new SqlServerDataSourceConfig(
                ConfigValues.str(map.get("username")),
                ConfigValues.str(map.get("password")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.SQLSERVER;
    }

    @Override
    public void validate() {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("SQL Server 数据源需要 username");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("SQL Server 数据源需要 password");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("username", username);
        map.put("password", password);
        return map;
    }

    private static void rejectUnknownKeys(Map<String, Object> map, Set<String> allowed) {
        map.keySet().stream()
                .filter(k -> !allowed.contains(k))
                .findFirst()
                .ifPresent(k -> {
                    throw new IllegalArgumentException("未知字段: " + k);
                });
    }
}
