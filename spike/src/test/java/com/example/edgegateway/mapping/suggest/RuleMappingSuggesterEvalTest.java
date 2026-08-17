package com.example.edgegateway.mapping.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用评测集（tools/evaluation/eval-set.json）验收规则引擎，断言与 Python 原型一致的基线：
 * 冷启动配对 12/18，带历史 18/18。
 */
class RuleMappingSuggesterEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Case(
            String id,
            List<SourceField> sourceFields,
            List<TargetField> targetFields,
            List<Expected> expected
    ) {
    }

    private record Expected(String source, String target, String transform, String datePattern) {
    }

    @Test
    void matchesEvalBaseline() throws Exception {
        Path evalSet = Path.of("../tools/evaluation/eval-set.json")
                .toAbsolutePath().normalize();
        assertTrue(evalSet.toFile().exists(), "找不到评测集: " + evalSet);
        JsonNode root = MAPPER.readTree(evalSet.toFile());

        List<Case> cases = new ArrayList<>();
        for (JsonNode node : root.get("cases")) {
            List<SourceField> sources = new ArrayList<>();
            for (JsonNode f : node.get("source").get("fields")) {
                sources.add(new SourceField(
                        f.get("name").asText(),
                        f.get("type").asText(),
                        f.has("nullable") && f.get("nullable").asBoolean(),
                        toSamples(f.get("samples"))));
            }
            List<TargetField> targets = new ArrayList<>();
            for (JsonNode f : node.get("target").get("fields")) {
                targets.add(new TargetField(
                        f.get("name").asText(),
                        f.has("required") && f.get("required").asBoolean()));
            }
            List<Expected> expected = new ArrayList<>();
            for (JsonNode e : node.get("expected")) {
                expected.add(new Expected(
                        e.get("source").asText(),
                        e.get("target").asText(),
                        e.get("transform").asText(),
                        e.has("datePattern") ? e.get("datePattern").asText() : null));
            }
            cases.add(new Case(node.get("id").asText(), sources, targets, expected));
        }

        // 冷启动：无历史
        Stats cold = run(cases, List.of());
        printTable("冷启动（无映射历史）", cases, cold);

        // 带历史：全部 ground truth 作为已确认映射（同站点资产沉淀后）
        Set<String> history = new HashSet<>();
        for (Case c : cases) {
            for (Expected e : c.expected()) {
                history.add(NameNormalizer.normalize(e.source()).compact()
                        + "|" + NameNormalizer.normalize(e.target()).compact());
            }
        }
        List<HistoryMapping> historyMappings = history.stream()
                .map(k -> k.split("\\|", 2))
                .map(p -> new HistoryMapping(p[0], p[1]))
                .toList();
        Stats warm = run(cases, historyMappings);
        printTable("带历史（同站点资产沉淀后）", cases, warm);

        // 断言基线（与 Python 原型 tools/evaluation/auto_fill_rate.py 一致）
        assertEquals(18, cold.required(), "冷启动必填目标数");
        assertEquals(12, cold.suggested(), "冷启动覆盖度");
        assertEquals(12, cold.pairingOk(), "冷启动自动填充率应为 12/18");
        assertEquals(12, cold.transformOk(), "冷启动转换类型正确率应为 12/18");
        assertEquals(11, cold.fullOk(), "冷启动参数完整率应为 11/18");
        assertEquals(18, warm.pairingOk(), "带历史自动填充率应为 18/18");
        assertEquals(18, warm.transformOk(), "带历史转换类型正确率应为 18/18");
        assertEquals(16, warm.fullOk(), "带历史参数完整率应为 16/18");

        // 抽查：业务重命名冷启动不强猜、带历史可复用
        Map<String, MappingSuggestion> coldPo = top1ByTarget(
                new RuleMappingSuggester().suggest(
                        caseById(cases, "north-po-sync").sourceFields(),
                        caseById(cases, "north-po-sync").targetFields(),
                        List.of()));
        assertFalse(coldPo.containsKey("sheet_no"), "冷启动 sheet_no 不应有建议（业务重命名）");
        assertEquals(MatchSource.EXACT, coldPo.get("vendor_code").matchSource());

        Map<String, MappingSuggestion> warmPo = top1ByTarget(
                new RuleMappingSuggester().suggest(
                        caseById(cases, "north-po-sync").sourceFields(),
                        caseById(cases, "north-po-sync").targetFields(),
                        historyMappings));
        assertTrue(warmPo.containsKey("sheet_no"), "带历史 sheet_no 应有建议");
        assertEquals("order_no", warmPo.get("sheet_no").sourcePath());
        assertEquals(MatchSource.HISTORY, warmPo.get("sheet_no").matchSource());
    }

    // ---- 工具方法 ----

    private record Stats(int required, int suggested, int pairingOk, int transformOk, int fullOk) {
    }

    private Stats run(List<Case> cases, List<HistoryMapping> history) {
        RuleMappingSuggester suggester = new RuleMappingSuggester();
        int required = 0, suggested = 0, pairing = 0, transform = 0, full = 0;
        for (Case c : cases) {
            List<MappingSuggestion> suggestions = suggester.suggest(c.sourceFields(), c.targetFields(), history);
            Map<String, MappingSuggestion> top1 = top1ByTarget(suggestions);
            for (TargetField t : c.targetFields()) {
                if (!t.required()) {
                    continue;
                }
                required++;
                Expected exp = c.expected().stream()
                        .filter(e -> e.target().equals(t.name()))
                        .findFirst().orElse(null);
                if (exp == null) {
                    continue;
                }
                MappingSuggestion sug = top1.get(t.name());
                if (sug == null) {
                    continue;
                }
                suggested++;
                boolean pairingOk = NameNormalizer.normalize(sug.sourcePath()).compact()
                        .equals(NameNormalizer.normalize(exp.source()).compact());
                if (pairingOk) {
                    pairing++;
                }
                boolean transformOk = pairingOk && sug.transform().name().equals(exp.transform());
                if (transformOk) {
                    transform++;
                }
                boolean fullOk = transformOk;
                if (transformOk && exp.datePattern() != null) {
                    fullOk = exp.datePattern().equals(sug.datePattern());
                }
                if (fullOk) {
                    full++;
                }
            }
        }
        return new Stats(required, suggested, pairing, transform, full);
    }

    private Map<String, MappingSuggestion> top1ByTarget(List<MappingSuggestion> suggestions) {
        Map<String, MappingSuggestion> top1 = new LinkedHashMap<>();
        for (MappingSuggestion s : suggestions) {
            top1.putIfAbsent(s.targetName(), s);
        }
        return top1;
    }

    private Case caseById(List<Case> cases, String id) {
        return cases.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private List<Object> toSamples(JsonNode array) {
        List<Object> samples = new ArrayList<>();
        for (JsonNode v : array) {
            if (v.isNull()) {
                samples.add(null);
            } else if (v.isBoolean()) {
                samples.add(v.asBoolean());
            } else if (v.isIntegralNumber()) {
                samples.add(v.asLong());
            } else if (v.isNumber()) {
                samples.add(v.asDouble());
            } else {
                samples.add(v.asText());
            }
        }
        return samples;
    }

    private void printTable(String title, List<Case> cases, Stats stats) {
        System.out.println("== " + title + " ==");
        System.out.printf("合计: 必填 %d, 建议 %d, 配对 %d, 转换 %d, 完整 %d%n",
                stats.required(), stats.suggested(), stats.pairingOk(), stats.transformOk(), stats.fullOk());
        System.out.printf("自动填充率(配对) %.0f%% | 转换类型正确率 %.0f%% | 参数完整率 %.0f%%%n",
                pct(stats.pairingOk(), stats.required()),
                pct(stats.transformOk(), stats.required()),
                pct(stats.fullOk(), stats.required()));
    }

    private double pct(int n, int d) {
        return d == 0 ? 0 : n * 100.0 / d;
    }
}
