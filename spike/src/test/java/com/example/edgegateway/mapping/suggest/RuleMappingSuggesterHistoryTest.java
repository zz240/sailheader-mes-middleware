package com.example.edgegateway.mapping.suggest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射历史冲突与复用规则的专项测试：
 * 同键最近确认优先、历史带出确认参数、同目标多源按频次排序、跨源结构隔离。
 */
class RuleMappingSuggesterHistoryTest {

    private static final String TABLE_A = "po_order";
    private static final String TABLE_B = "purchase_order_v2";

    private static final List<SourceField> SOURCE_A = List.of(
            new SourceField("order_no", "VARCHAR", false, List.of("PO-1", "PO-2")),
            new SourceField("vendor_no", "VARCHAR", false, List.of("V001", "V002")),
            new SourceField("order_date", "DATETIME", true,
                    List.of("2026-08-14 10:00:00", "2026-08-15 11:00:00"))
    );

    private static final List<TargetField> TARGET_SHEET = List.of(
            new TargetField("sheet_no", true),
            new TargetField("sheet_date", true)
    );

    @Test
    void sameKeyLatestConfirmedWins() {
        // 同键两条历史：旧的确认 STRING，新的确认 ENUM（业务改过口径）→ 以最近为准
        List<HistoryMapping> history = List.of(
                new HistoryMapping(TABLE_A, "order_no", "sheet_no",
                        TransformKind.STRING, null, null, null, 3, 1000L),
                new HistoryMapping(TABLE_A, "order_no", "sheet_no",
                        TransformKind.ENUM, null, null, Map.of("PO-1", "PO-ORDER-1"), 1, 2000L)
        );
        MappingSuggestion suggestion = top1(suggest(TABLE_A, history)).get("sheet_no");

        assertEquals(MatchSource.HISTORY, suggestion.matchSource());
        assertEquals(TransformKind.ENUM, suggestion.transform(), "同键冲突应以最近确认为准");
        assertEquals("PO-ORDER-1", suggestion.enumMap().get("PO-1"), "应带出最近确认的枚举映射");
    }

    @Test
    void historyCarriesConfirmedTransformParams() {
        // 样例推断会给出 HH:mm:ss，但历史确认过 yyyy-MM-dd → 复用时带出历史格式
        List<HistoryMapping> history = List.of(
                new HistoryMapping(TABLE_A, "order_date", "sheet_date",
                        TransformKind.DATE, "yyyy-MM-dd", null, null, 2, 1000L)
        );
        MappingSuggestion suggestion = top1(suggest(TABLE_A, history)).get("sheet_date");

        assertEquals(MatchSource.HISTORY, suggestion.matchSource());
        assertEquals("yyyy-MM-dd", suggestion.datePattern());
    }

    @Test
    void sameTargetMultipleSourcesRankedByHitCount() {
        // 同一目标两条历史候选（都 1.0）：order_no 用过 5 次、vendor_no 用过 2 次 → 取高频
        List<HistoryMapping> history = List.of(
                new HistoryMapping(TABLE_A, "vendor_no", "sheet_no",
                        TransformKind.STRING, null, null, null, 2, 2000L),
                new HistoryMapping(TABLE_A, "order_no", "sheet_no",
                        TransformKind.STRING, null, null, null, 5, 1000L)
        );
        MappingSuggestion suggestion = top1(suggest(TABLE_A, history)).get("sheet_no");

        assertEquals("order_no", suggestion.sourcePath(), "同分候选应按复用次数排序");
    }

    @Test
    void historyIsolatedBySourceStructure() {
        // 表 A 确认过 out_no → outOrderNo，但表 B 的同名字段不应复用（防跨表语义污染）
        List<HistoryMapping> history = List.of(
                new HistoryMapping(TABLE_A, "out_no", "outOrderNo",
                        TransformKind.STRING, null, null, null, 9, 1000L)
        );
        SourceStructure other = new SourceStructure(TABLE_B, List.of(
                new SourceField("out_no", "VARCHAR", false, List.of("SO-1", "SO-2"))));
        List<MappingSuggestion> suggestions = new RuleMappingSuggester()
                .suggest(other, List.of(new TargetField("outOrderNo", true)), history);

        assertFalse(suggestions.stream().anyMatch(s -> s.matchSource() == MatchSource.HISTORY),
                "不同源结构的历史不应串用");
        assertTrue(suggestions.isEmpty(), "冷启动相似度低于阈值时不应强猜");
    }

    // ---- 工具方法 ----

    private List<MappingSuggestion> suggest(String sourceKey, List<HistoryMapping> history) {
        return new RuleMappingSuggester().suggest(
                new SourceStructure(sourceKey, SOURCE_A),
                TARGET_SHEET,
                history);
    }

    private Map<String, MappingSuggestion> top1(List<MappingSuggestion> suggestions) {
        Map<String, MappingSuggestion> top1 = new java.util.LinkedHashMap<>();
        for (MappingSuggestion s : suggestions) {
            top1.putIfAbsent(s.targetName(), s);
        }
        return top1;
    }
}
