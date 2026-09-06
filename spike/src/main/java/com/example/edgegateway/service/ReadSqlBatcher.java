package com.example.edgegateway.service;

import com.example.edgegateway.config.ReadSqlConfig;
import com.example.edgegateway.model.WatermarkType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * READ_SQL 增量/刷量策略与 batches 逻辑（#79）。
 *
 * <p>这是"水位策略重构 + 分批"的深 module：把两种策略的谓词、批查询 SQL、批内水位推进、
 * 分批终止判断与时间边界换算收敛到一处，便于单测与复用。路由生成器（{@code RouteGenerator}）
 * 只负责把它接到 Camel 循环上，不重复这些规则。</p>
 *
 * <ul>
 *   <li>{@code SEQUENCE}＝增量（id）：谓词 {@code col > ?}，水位取批内 max(id)。源只增/少更新。</li>
 *   <li>{@code TIMESTAMP}＝刷量（按更新时间 upsert）：谓词 {@code col >= ?}（含边界，幂等可重跑），
 *       水位取批内 max(col) 当作 epoch 毫秒保存——<b>不再用墙钟 now</b>。</li>
 * </ul>
 *
 * <p>分批为 keyset 分页：{@code WHERE 谓词 ORDER BY col LIMIT batchSize}；每次读入一批，
 * 每批处理完才推进水位（由外层循环保证"批失败只丢该批"）。首次全量从空水位（0）开始逐批扫完。</p>
 *
 * <p><b>边界约定</b>：TIMESTAMP 用 {@code >=}（含边界，配合下游业务键 upsert 幂等，无边界漏单）。
 * 因此若源表 {@code updated_at} 存在<b>大量相同时间点的行</b>，单列 keyset 会重读边界行；建议源时间列用
 * 亚秒精度，或依赖幂等 upsert + 周期性全量重扫。SEQUENCE（自增 id 唯一）无此问题。</p>
 *
 * <p><b>时区</b>：窗口下界按任务时区（{@code Job.timezone}）格式化（见 {@link #formatTimeBound}）。
 * 为读对，源库的 JDBC 会话时区与任务时区应对齐；本模块只负责按任务时区换算，不主动改源库会话时区。</p>
 */
@Component
public class ReadSqlBatcher {

    /** 时间边界格式化（与 DB 会话一致的基础格式，时区用任务时区）。 */
    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 批大小：空取默认；非法抛可定位错误。 */
    public int batchSize(ReadSqlConfig read) {
        Integer size = read.batchSize();
        if (size == null) {
            return ReadSqlConfig.DEFAULT_BATCH_SIZE;
        }
        if (size <= 0) {
            throw new IllegalArgumentException("批大小 batchSize 必须为正整数: " + size);
        }
        return size;
    }

    /** 水位策略；未配置回退 SEQUENCE（保持存量"默认自增"语义）。 */
    public WatermarkType parseType(ReadSqlConfig read) {
        if (read.watermarkType() == null || read.watermarkType().isBlank()) {
            return WatermarkType.SEQUENCE;
        }
        return WatermarkType.valueOf(read.watermarkType());
    }

    /**
     * 水位谓词占位（替换 SQL 里的 {@code {watermark}}）。
     * SEQUENCE 严格 {@code >}（避免同 id 重复处理）；TIMESTAMP 用 {@code >=}（含边界，配合下游业务键 upsert 幂等）。
     * 使用 {@code ?} 占位以便 {@link org.springframework.jdbc.core.JdbcTemplate} 绑定，不用墙钟。
     */
    public String predicate(ReadSqlConfig read) {
        return parseType(read) == WatermarkType.TIMESTAMP
                ? read.watermarkColumn() + " >= ?"
                : read.watermarkColumn() + " > ?";
    }

    /** 批查询完整 SQL：替换谓词 + keyset 排序 + LIMIT batchSize；水位列缺失则原样返回（无分批）。 */
    public String batchSql(ReadSqlConfig read) {
        if (read.watermarkColumn() == null || read.watermarkColumn().isBlank()) {
            return read.sql();
        }
        return read.sql().replace("{watermark}", predicate(read))
                + " ORDER BY " + read.watermarkColumn() + " LIMIT " + batchSize(read);
    }

    /** 某批加工后应推进到的水位值（只前不后退，与本次读取前水位取 max）。 */
    public long newBatchWatermark(ReadSqlConfig read, List<Map<String, Object>> rows, long current, ZoneId zone) {
        long value = current;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            value = Math.max(value, toEpochMillis(row.get(read.watermarkColumn()), zone));
        }
        return value;
    }

    /** 是否还有下一批：读满一整批（含边界）就继续；否则结束（尾批/空批）。 */
    public boolean hasMore(ReadSqlConfig read, List<Map<String, Object>> rows) {
        // LIMIT batchSize 决定了返回行数至多 batchSize，故"读满一页"即 size == batchSize（空批 size=0 自然为 false）。
        // 用 == 精确表达，避免"会不会超过"的歧义。
        return rows.size() == batchSize(read);
    }

    /** 把持久化的 epoch 毫秒水位格式化为时间窗口下界字符串（任务时区）。 */
    public String formatTimeBound(long epochMillis, ZoneId zone) {
        return BOUND_FORMAT.withZone(zone).format(Instant.ofEpochMilli(epochMillis));
    }

    /** 把源行某列值统一换算为 epoch 毫秒（时间列）或数值（自增列，按 long 处理）。 */
    public long toEpochMillis(Object raw, ZoneId zone) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.getTime();
        }
        if (raw instanceof java.util.Date d) {
            return d.getTime();
        }
        if (raw instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (raw instanceof OffsetDateTime odt) {
            return odt.toInstant().toEpochMilli();
        }
        if (raw instanceof ZonedDateTime zdt) {
            return zdt.toInstant().toEpochMilli();
        }
        if (raw instanceof LocalDateTime ldt) {
            return ldt.atZone(zone).toInstant().toEpochMilli();
        }
        if (raw instanceof LocalDate ld) {
            return ld.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        if (raw instanceof CharSequence s) {
            return parseTimeString(s.toString(), zone);
        }
        throw new IllegalArgumentException("无法把水位列值换算为毫秒: " + raw + "（" + raw.getClass().getSimpleName() + "）");
    }

    private long parseTimeString(String text, ZoneId zone) {
        String trimmed = text.trim();
        try {
            return LocalDateTime.parse(trimmed, BOUND_FORMAT).atZone(zone).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // fall through
        }
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无法解析时间边界字符串: " + text, e);
        }
    }
}
