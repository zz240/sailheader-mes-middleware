package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MySQL 数据源配置：url、username、password 都必填。 */
public record MysqlDataSourceConfig(String url, String username, String password)
        implements DataSourceConfig {

    public static MysqlDataSourceConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("url", "username", "password"));
        return new MysqlDataSourceConfig(
                ConfigValues.str(map.get("url")),
                ConfigValues.str(map.get("username")),
                ConfigValues.str(map.get("password")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public void validate() {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MySQL 数据源需要 url");
        }
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
        map.put("url", url);
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
