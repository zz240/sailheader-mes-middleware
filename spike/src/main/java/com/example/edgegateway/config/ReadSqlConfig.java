package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MySQL 读动作：数据源 + SQL + 水位策略/列（DS-7 从任务移入动作）+ 业务键源列（#70）+ 分批大小（#79）。
 *
 * <p>两种水位策略（#79）：</p>
 * <ul>
 *   <li>{@code SEQUENCE}＝增量（id）：谓词 {@code col > :#lastId}，水位取批内 max(id)，适合"源只增/少更新"。</li>
 *   <li>{@code TIMESTAMP}＝刷量/全量（按更新时间 upsert）：谓词 {@code col >= :#lastRun}，水位取批内 max(col)（不用墙钟 now），
 *       幂等、无边界漏单、可安全重跑；下游按业务键 upsert。</li>
 * </ul>
 */
public record ReadSqlConfig(
        Long dataSourceId,
        String sql,
        String watermarkType,
        String watermarkColumn,
        String businessKey,
        Integer batchSize
) implements ActionConfig {

    /** 默认每批读取行数（#79），缺省 500。 */
    public static final int DEFAULT_BATCH_SIZE = 500;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    public static ReadSqlConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "sql", "watermarkType", "watermarkColumn", "businessKey", "batchSize"));
        Object id = map.get("dataSourceId");
        Object batch = map.get("batchSize");
        return new ReadSqlConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("sql")),
                ConfigValues.str(map.get("watermarkType")),
                ConfigValues.str(map.get("watermarkColumn")),
                ConfigValues.str(map.get("businessKey")),
                batch == null ? null : Integer.valueOf(batch.toString()));
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
        if (batchSize != null) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("批大小 batchSize 必须为正整数: " + batchSize);
            }
            if (batchSize > 10000) {
                throw new IllegalArgumentException("批大小 batchSize 过大（上限 10000）: " + batchSize);
            }
        }
        if (watermarkType != null && !watermarkType.isBlank()) {
            if (!"SEQUENCE".equals(watermarkType) && !"TIMESTAMP".equals(watermarkType)) {
                throw new IllegalArgumentException("水位策略只能是 SEQUENCE（增量 id）或 TIMESTAMP（按时间刷量）: " + watermarkType);
            }
            if (watermarkColumn == null || watermarkColumn.isBlank()) {
                throw new IllegalArgumentException("设置了水位策略就必须指定水位列 watermarkColumn");
            }
        }
        if (watermarkColumn != null && !watermarkColumn.isBlank() && !IDENTIFIER.matcher(watermarkColumn).matches()) {
            throw new IllegalArgumentException("水位列应为源表列名（字母/数字/下划线）: " + watermarkColumn);
        }
        if (sql != null && sql.contains("{watermark}") && (watermarkColumn == null || watermarkColumn.isBlank())) {
            throw new IllegalArgumentException("SQL 使用了 {watermark} 占位就必须指定水位列 watermarkColumn");
        }
        if (businessKey != null && !businessKey.isBlank() && !businessKey.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("业务键应为源表列名（字母/数字/下划线，点号/空格非法）: " + businessKey);
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
        if (businessKey != null) {
            map.put("businessKey", businessKey);
        }
        if (batchSize != null) {
            map.put("batchSize", batchSize);
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
