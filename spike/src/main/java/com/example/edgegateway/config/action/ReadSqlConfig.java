package com.example.edgegateway.config.action;

import com.example.edgegateway.config.ConfigValues;
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
 *
 * <p><b>{@code {watermark}} 是整个**谓词**的占位符，不是值占位符</b>：写法只有
 * {@code … where {watermark}}，框架按策略替换成 {@code 水位列 > ?} / {@code 水位列 >= ?}
 * 并配 {@code ORDER BY 水位列 LIMIT batchSize}。别自己再写列名与比较符
 * （写成 {@code and h.FModifyDate >= {watermark}} 会替换成
 * {@code h.FModifyDate >= h.FModifyDate >= ?}——恒不成立，一条也读不回来且不报错，
 * #140 真机就是这么空转的）。</p>
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
        // 反过来那一半：配了水位列、SQL 里却没有 {watermark} 作为谓词的落点。
        // 这一档**运行期会无限循环**，所以必须在配置期拒掉：批查询仍会按水位列排序分页
        // （{@code ORDER BY 水位列 LIMIT batchSize}），但每一批读回来的都是同一页、水位永远不动，
        // 于是「读满一页 → 还有下一批」恒真，一路读到天荒地老（#140 真机第一次跑就是这么卡住的：
        // 读批还先撞上「没有占位符却绑了参数」的参数越界，把真正的死循环盖住了）。
        if (watermarkColumn != null && !watermarkColumn.isBlank() && sql != null && !sql.contains("{watermark}")) {
            throw new IllegalArgumentException(
                    "配了水位列 watermarkColumn 就必须在 SQL 里用 {watermark} 标记谓词的位置"
                            + "（它会被替换成 `" + watermarkColumn + " > ?` 之类，并配 ORDER BY + LIMIT 分批）；"
                            + "本次查询没有谓词可放（如聚合查询），请**去掉水位列**改用全量单次读取，"
                            + "否则每批读到的是同一页、水位不动，会一直循环下去: " + watermarkColumn);
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
