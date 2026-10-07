package com.example.edgegateway.service;

import com.example.edgegateway.config.action.ReadSqlConfig;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.service.sql.SqlDialect;
import com.example.edgegateway.service.sql.SqlDialects;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * READ_SQL 水位策略重构 + 分批（#79）的纯逻辑单测：
 * 两种策略谓词（增量 {@code >} / 刷量 {@code >=} 幂等）、批大小、keyset 分页 SQL、
 * 批内水位推进、分批终止、时间边界换算（任务时区）。
 */
class ReadSqlBatcherTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final ReadSqlBatcher batcher = new ReadSqlBatcher();
    private final SqlDialect mysql = SqlDialects.of(DataSourceType.MYSQL);
    private final SqlDialect sqlserver = SqlDialects.of(DataSourceType.SQLSERVER);

    private ReadSqlConfig sequence(String column) {
        return new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                "SEQUENCE", column, "order_no", 3);
    }

    private ReadSqlConfig timestamp(String column) {
        return new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                "TIMESTAMP", column, "order_no", 3);
    }

    @Test
    void sequenceUsesStrictGreaterPredicate() {
        ReadSqlConfig read = sequence("id");
        assertThat(batcher.predicate(read)).isEqualTo("id > ?");
        assertThat(batcher.parseType(read)).isEqualTo(com.example.edgegateway.model.WatermarkType.SEQUENCE);
    }

    @Test
    void timestampUsesInclusivePredicateForIdempotentBoundaryReRead() {
        ReadSqlConfig read = timestamp("updated_at");
        // >= 含边界：重复执行会重读同一批边界行，配合下游业务键 upsert 幂等、无边界漏单
        assertThat(batcher.predicate(read)).isEqualTo("updated_at >= ?");
        assertThat(batcher.parseType(read)).isEqualTo(com.example.edgegateway.model.WatermarkType.TIMESTAMP);
    }

    @Test
    void batchSqlAppendsKeysetOrderByAndLimitWithConfiguredBatchSize() {
        ReadSqlConfig read = sequence("id");
        assertThat(batcher.batchSql(read, mysql))
                .isEqualTo("select * from po_order where id > ? ORDER BY id LIMIT 3");
    }

    /** 分页写法归方言（ADR-0014）：同一个配置在 SQL Server 上必须是 OFFSET…FETCH，不能是 LIMIT。 */
    @Test
    void sqlServerBatchSqlPaginatesWithOffsetFetch() {
        ReadSqlConfig read = sequence("FInterID");
        assertThat(batcher.batchSql(read, sqlserver))
                .isEqualTo("select * from po_order where FInterID > ?"
                        + " ORDER BY FInterID OFFSET 0 ROWS FETCH NEXT 3 ROWS ONLY");
    }

    @Test
    void batchSizeDefaultWhenUnset() {
        ReadSqlConfig read = new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                "SEQUENCE", "id", null, null);
        assertThat(batcher.batchSql(read, mysql)).endsWith("LIMIT " + ReadSqlConfig.DEFAULT_BATCH_SIZE);
    }

    /**
     * 配了水位列、但 SQL 里没有 {@code {watermark}}（谓词无处可放：聚合查询、或源表没有可用的水位列）时，
     * 批查询**仍然会排序分页**（{@code ORDER BY 水位列 LIMIT batchSize}），但**没有参数占位符**。
     *
     * <p>两件事各有一处结论：</p>
     * <ul>
     *   <li>「仍分页、却没有谓词」意味着每批读回同一页、水位不动 → 运行期**无限循环**。
     *       所以这种配置由 {@link ReadSqlConfig#validate()} 在配置期拒掉（见
     *       {@code watermarkColumnWithoutPlaceholderRejected}）。</li>
     *   <li>「没有占位符」意味着读批**不能绑参数**——真机上无条件绑 bound 时报
     *       {@code Parameter index out of range (1 > number of parameters, which is 0)}；
     *       路由侧因此按「SQL 里有没有 {@code ?}」决定绑不绑。</li>
     * </ul>
     */
    @Test
    void sqlWithoutWatermarkPlaceholderStillPaginatesButCarriesNoParameter() {
        ReadSqlConfig read = new ReadSqlConfig(1L, "select count(*) as n from po_order",
                "SEQUENCE", "id", null, 3);

        String sql = batcher.batchSql(read, mysql);

        assertThat(sql).isEqualTo("select count(*) as n from po_order ORDER BY id LIMIT 3");
        assertThat(sql).as("没有占位符就没有参数可绑（路由侧据此决定不传 bound）").doesNotContain("?");
    }

    @Test
    void invalidBatchSizeGivesLocatableError() {
        ReadSqlConfig read = new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                "SEQUENCE", "id", null, 0);
        assertThatThrownBy(() -> batcher.batchSql(read, mysql))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void sequenceNewWatermarkPerBatchTakesMaxId() {
        ReadSqlConfig read = sequence("id");
        List<Map<String, Object>> batch = List.of(
                row("id", 1L), row("id", 2L), row("id", 3L));
        // 从 0 起：首批推到 3
        assertThat(batcher.newBatchWatermark(read, batch, 0L, SHANGHAI)).isEqualTo(3L);
        // 从不后退：即使批内最大值小于当前，也保留当前
        assertThat(batcher.newBatchWatermark(read, List.of(row("id", 2L)), 5L, SHANGHAI)).isEqualTo(5L);
    }

    @Test
    void timestampNewWatermarkUsesBatchMaxUpdatedAtNotWallClock() {
        ReadSqlConfig read = timestamp("updated_at");
        long t1 = LocalDateTime.of(2026, 8, 14, 10, 0, 0).atZone(SHANGHAI).toInstant().toEpochMilli();
        long t2 = LocalDateTime.of(2026, 8, 14, 11, 0, 0).atZone(SHANGHAI).toInstant().toEpochMilli();
        List<Map<String, Object>> batch = List.of(
                row("updated_at", new Timestamp(t1)), row("updated_at", new Timestamp(t2)));
        // 不用墙钟 now：水位 = 批内 max(updated_at) 对应的 epoch
        assertThat(batcher.newBatchWatermark(read, batch, 0L, SHANGHAI)).isEqualTo(t2);
    }

    @Test
    void hasMoreOnlyWhileFullBatch() {
        ReadSqlConfig read = sequence("id");
        assertThat(batcher.hasMore(read, List.of(row("id", 1), row("id", 2), row("id", 3)))).isTrue();
        assertThat(batcher.hasMore(read, List.of(row("id", 1), row("id", 2)))).isFalse();
        assertThat(batcher.hasMore(read, List.of())).isFalse();
    }

    @Test
    void firstFullLoadAdvancesWatermarkPerBatchUntilExhausted() {
        ReadSqlConfig read = sequence("id");
        // 首跑：空水位 0 → 3 → 6 → 7，逐批扫完（7 行、batchSize 3 = 3 批）
        List<Map<String, Object>> b1 = List.of(row("id", 1), row("id", 2), row("id", 3));
        List<Map<String, Object>> b2 = List.of(row("id", 4), row("id", 5), row("id", 6));
        List<Map<String, Object>> b3 = List.of(row("id", 7));

        long wm = 0L;
        wm = batcher.newBatchWatermark(read, b1, wm, SHANGHAI);
        assertThat(wm).isEqualTo(3L);
        assertThat(batcher.hasMore(read, b1)).isTrue();
        wm = batcher.newBatchWatermark(read, b2, wm, SHANGHAI);
        assertThat(wm).isEqualTo(6L);
        assertThat(batcher.hasMore(read, b2)).isTrue();
        wm = batcher.newBatchWatermark(read, b3, wm, SHANGHAI);
        assertThat(wm).isEqualTo(7L);
        assertThat(batcher.hasMore(read, b3)).isFalse(); // 尾批 < batchSize，终止
    }

    @Test
    void timeBoundFormattedInJobTimezone() {
        long epoch = LocalDateTime.of(2026, 8, 14, 10, 0, 0).atZone(SHANGHAI).toInstant().toEpochMilli();
        assertThat(batcher.formatTimeBound(epoch, SHANGHAI)).isEqualTo("2026-08-14 10:00:00");
        // 同一时刻在 UTC 显示为 02:00（即时区窗口漂移被显式处理而非隐匿）
        assertThat(batcher.formatTimeBound(epoch, ZoneId.of("UTC"))).isEqualTo("2026-08-14 02:00:00");
    }

    @Test
    void toEpochMillisHandlesCommonColumnReadings() {
        long epoch = LocalDateTime.of(2026, 8, 14, 10, 0, 0).atZone(SHANGHAI).toInstant().toEpochMilli();
        assertThat(batcher.toEpochMillis(new Timestamp(epoch), SHANGHAI)).isEqualTo(epoch);
        assertThat(batcher.toEpochMillis(epoch, SHANGHAI)).isEqualTo(epoch);
        assertThat(batcher.toEpochMillis(LocalDateTime.of(2026, 8, 14, 10, 0, 0), SHANGHAI)).isEqualTo(epoch);
        assertThat(batcher.toEpochMillis("2026-08-14 10:00:00", SHANGHAI)).isEqualTo(epoch);
    }

    @Test
    void timeColumnRequiredWhenStrategyConfigUnavailable() {
        // 时间列不可用：设置了水位策略但未给水位列 → 配置校验应给出定位错误
        ReadSqlConfig read = new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                "TIMESTAMP", null, null, null);
        assertThatThrownBy(read::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("watermarkColumn");
    }

    @Test
    void invalidWatermarkColumnIdentifiersRejected() {
        ReadSqlConfig read = new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                "SEQUENCE", "id; drop table x", null, null);
        assertThatThrownBy(read::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("水位列");
    }

    @Test
    void watermarkPlaceholderWithoutColumnRejected() {
        ReadSqlConfig read = new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                null, null, null, null);
        assertThatThrownBy(read::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{watermark}");
    }

    /**
     * 反向那一半（#140 真机抓到的死循环）：配了水位列、SQL 里却没有 {@code {watermark}} 作谓词的落点。
     *
     * <p>这一档批查询仍会 {@code ORDER BY 水位列 LIMIT batchSize}，但每批读回同一页、水位不动，
     * 「读满一页 → 还有下一批」恒真 → 无限循环。配置期必须拒掉，别等运行期挂住。</p>
     */
    @Test
    void watermarkColumnWithoutPlaceholderRejected() {
        ReadSqlConfig read = new ReadSqlConfig(1L, "select count(*) as n from po_order",
                "SEQUENCE", "id", null, 3);
        assertThatThrownBy(read::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{watermark}")
                .hasMessageContaining("去掉水位列");
    }

    @Test
    void watermarkColumnWithPlaceholderPasses() {
        ReadSqlConfig read = new ReadSqlConfig(1L, "select * from po_order where {watermark}",
                "SEQUENCE", "id", null, 3);
        read.validate(); // 不抛即可
    }

    private Map<String, Object> row(String key, Object value) {
        return Map.of(key, value);
    }
}
