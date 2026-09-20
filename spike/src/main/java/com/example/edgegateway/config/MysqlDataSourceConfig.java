package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * MySQL 数据源配置（连接级，ADR-0007）：连接 url 取数据源 {@code address}，addition 仅存 username/password。
 */
public record MysqlDataSourceConfig(String username, String password)
        implements JdbcDataSourceConfig {

    public static MysqlDataSourceConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("username", "password"));
        return new MysqlDataSourceConfig(
                ConfigValues.str(map.get("username")),
                ConfigValues.str(map.get("password")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public void validate() {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("MySQL 数据源需要 username");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("MySQL 数据源需要 password");
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
