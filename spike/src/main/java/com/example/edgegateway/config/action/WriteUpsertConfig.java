package com.example.edgegateway.config.action;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MySQL 幂等 upsert 写动作：数据源 + tableName + idempotencyKey。 */
public record WriteUpsertConfig(
        Long dataSourceId,
        String tableName,
        String idempotencyKey
) implements ActionConfig {

    public static WriteUpsertConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "tableName", "idempotencyKey"));
        Object id = map.get("dataSourceId");
        return new WriteUpsertConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("tableName")),
                ConfigValues.str(map.get("idempotencyKey")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.WRITE_UPSERT;
    }

    @Override
    public void validate() {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("upsert 写动作需要 dataSourceId");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("upsert 写动作需要 tableName");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("upsert 写动作需要 idempotencyKey");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataSourceId", dataSourceId);
        if (tableName != null) {
            map.put("tableName", tableName);
        }
        if (idempotencyKey != null) {
            map.put("idempotencyKey", idempotencyKey);
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
