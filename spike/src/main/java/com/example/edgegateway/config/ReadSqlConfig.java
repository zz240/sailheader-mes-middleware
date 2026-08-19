package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MySQL 读动作：数据源 + SQL + 水位策略/列（DS-7 从任务移入动作）。 */
public record ReadSqlConfig(
        Long dataSourceId,
        String sql,
        String watermarkType,
        String watermarkColumn
) implements ActionConfig {

    public static ReadSqlConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "sql", "watermarkType", "watermarkColumn"));
        Object id = map.get("dataSourceId");
        return new ReadSqlConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("sql")),
                ConfigValues.str(map.get("watermarkType")),
                ConfigValues.str(map.get("watermarkColumn")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.READ_SQL;
    }

    @Override
    public void validate() {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("读 SQL 动作需要 dataSourceId");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("读 SQL 动作需要 sql");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataSourceId", dataSourceId);
        if (sql != null) {
            map.put("sql", sql);
        }
        if (watermarkType != null) {
            map.put("watermarkType", watermarkType);
        }
        if (watermarkColumn != null) {
            map.put("watermarkColumn", watermarkColumn);
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
