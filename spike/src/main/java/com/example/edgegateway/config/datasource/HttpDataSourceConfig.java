package com.example.edgegateway.config.datasource;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.DataSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 数据源配置（连接级，ADR-0007）：address 为根地址，token 为鉴权凭证；
 * path/method 移入动作 addition。
 */
public record HttpDataSourceConfig(String token) implements DataSourceConfig {

    public static HttpDataSourceConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("token"));
        return new HttpDataSourceConfig(ConfigValues.str(map.get("token")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.HTTP;
    }

    @Override
    public void validate() {
        // 无必填字段：地址在根上，鉴权 token 可选
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (token != null) {
            map.put("token", token);
        }
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
