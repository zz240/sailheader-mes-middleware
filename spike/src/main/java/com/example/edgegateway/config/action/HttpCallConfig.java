package com.example.edgegateway.config.action;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** HTTP 调用动作：数据源（根地址）+ path + method + bodySourceStep（默认上一个动作输出）。 */
public record HttpCallConfig(
        Long dataSourceId,
        String path,
        String method,
        Integer bodySourceStep
) implements ActionConfig {

    public static HttpCallConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "path", "method", "bodySourceStep"));
        Object id = map.get("dataSourceId");
        return new HttpCallConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("path")),
                ConfigValues.str(map.get("method")),
                map.get("bodySourceStep") == null ? null : Integer.valueOf(map.get("bodySourceStep").toString()));
    }

    @Override
    public ActionKind type() {
        return ActionKind.HTTP_CALL;
    }

    @Override
    public void validate() {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("HTTP 调用动作需要 dataSourceId");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataSourceId", dataSourceId);
        if (path != null) {
            map.put("path", path);
        }
        if (method != null) {
            map.put("method", method);
        }
        if (bodySourceStep != null) {
            map.put("bodySourceStep", bodySourceStep);
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
