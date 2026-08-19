package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** HTTP 数据源配置：请求方法必填，token 可选（北向 Bearer Token 鉴权）。 */
public record HttpDataSourceConfig(String method, String token) implements DataSourceConfig {

    public static HttpDataSourceConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("method", "token"));
        return new HttpDataSourceConfig(
                ConfigValues.str(map.get("method")),
                ConfigValues.str(map.get("token")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.HTTP;
    }

    @Override
    public void validate() {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("HTTP 数据源需要 method");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", method);
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
