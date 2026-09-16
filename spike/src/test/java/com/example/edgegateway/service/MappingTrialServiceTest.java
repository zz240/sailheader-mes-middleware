package com.example.edgegateway.service;

import com.example.edgegateway.web.dto.MappingTrialRequest;
import com.example.edgegateway.web.dto.MappingTrialResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「试算」那个只读接口的 fixture 驱动测试（#116）。
 *
 * <p>断言的全是外部行为：给一份草稿 + 一份来源样例，返回的目标报文是什么、没跑通时列出哪几行。
 * 不断言它内部先跑了什么、后跑了什么。</p>
 *
 * <p>fixture 与「校验配置」那份**同源**（#110 里写明的「两边共用同一份目标契约与同一份样例数据」）：
 * 同一份目标契约（制品 → BOM，{@code docs/research/mapping-framework.md} §4.1）与同一份样例数据
 * （{@code erp测试数据.json}）。</p>
 */
class MappingTrialServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MappingTrialService service = new MappingTrialService(MAPPER);

    /** 整份跑通：给出完整目标报文，数组映射是目标数组的多行（#116 验收三、四）。 */
    @Test
    void aRunnableDraftGivesTheWholeTargetMessage() {
        MappingTrialResult result = service.trial(new MappingTrialRequest(bomDraft(), erpBomJson(), 1));

        assertThat(result.ran()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.failures()).isEmpty();
        Map<String, Object> bom = bomOf(result);
        assertThat(bom.get("bomNo")).isEqualTo("BOM-PRD-BUMPER-001");
        assertThat(bom.get("productCode")).isEqualTo("PRD-BUMPER-001");
        assertThat(bom.get("productName")).isEqualTo("前保险杠");
        // 数组映射：来源两条 → 目标数组两行，行号与转换都真跑过了
        assertThat(bomLinesOf(result)).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) bom.get("bomLines");
        assertThat(lines.get(0)).containsEntry("lineNo", 1).containsEntry("materialCode", "MAT-718H")
                .containsEntry("unit", "件");
        assertThat(lines.get(1)).containsEntry("lineNo", 2).containsEntry("materialCode", "MAT-SKD61")
                .containsEntry("unit", "件");
        // 数字转换出来的是什么类型由引擎定（$number 恒给浮点），只断言值
        assertThat(((Number) lines.get(0).get("qty")).intValue()).isEqualTo(1);
        assertThat(((Number) lines.get(1).get("qty")).intValue()).isEqualTo(24);
    }

    /**
     * 整份没跑通时**逐行归因**（#116 验收五）：报出单独跑也失败的那一行，带上它所属的数组行。
     *
     * <p>样例里第 2 条明细的数量是文本（`"abc"`），`$number` 会抛——所以是 `qty` 那一行的问题，
     * 不是整组的问题：`materialCode` 那一行单独跑是好的，不许跟着一起标红。</p>
     */
    @Test
    void aBrokenRowIsAttributedToItsOwnRowUnderTheGroup() {
        MappingTrialResult result = service.trial(
                new MappingTrialRequest(bomDraft(), erpBomJsonWithTextQuantity(), 1));

        assertThat(result.ran()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.error()).isNotBlank();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).target()).isEqualTo("qty");
        assertThat(result.failures().get(0).parent()).isEqualTo("bom.bomLines");
        assertThat(result.failures().get(0).message()).isNotBlank();
    }

    /** 顶层行自己的问题：报它自己，没有所属数组行（`parent` 是 null）。 */
    @Test
    void aBrokenTopLevelRowIsAttributedToItself() {
        Map<String, Object> draft = Map.of("rules", List.of(
                new LinkedHashMap<>(Map.of("sourceStep", 1, "source", "productCode",
                        "target", "bom.productCode", "transform", "INT"))));

        MappingTrialResult result = service.trial(new MappingTrialRequest(draft, erpBomJson(), 1));

        assertThat(result.ran()).isFalse();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).target()).isEqualTo("bom.productCode");
        assertThat(result.failures().get(0).parent()).isNull();
    }

    /**
     * 整份样例是一个数组时按**第 1 条**跑——与推字段清单同一条口径（#115）：左边那份清单当初就是从
     * 第 1 条推出来的，试算跑的必须是同一份数据，否则界面上写的和右边跑的不是一回事。
     */
    @Test
    void anArraySampleIsRunOnItsFirstItem() {
        String array = "[" + erpBomJson() + ", {\"productCode\":\"PRD-2\",\"productName\":\"另一个\"}]";

        MappingTrialResult result = service.trial(new MappingTrialRequest(bomDraft(), array, 1));

        assertThat(result.ran()).isTrue();
        assertThat(bomOf(result).get("productCode")).isEqualTo("PRD-BUMPER-001");
        assertThat(bomLinesOf(result)).hasSize(2);
    }

    /** 手写 JSONata 也能试算（#116 验收六）：那一整段就是这次映射要执行的东西。 */
    @Test
    void anExpressionDraftCanBeTrialRunToo() {
        Map<String, Object> draft = Map.of(
                "mode", "EXPRESSION",
                "expression", "{ \"bomNo\": \"BOM-\" & $s_1.productCode, \"items\": $count($s_1.items) }");

        MappingTrialResult result = service.trial(new MappingTrialRequest(draft, erpBomJson(), 1));

        assertThat(result.ran()).isTrue();
        assertThat(result.output()).isEqualTo(Map.of("bomNo", "BOM-PRD-BUMPER-001", "items", 2));
    }

    /** 手写表达式跑不通时：整份那句话给出来，没有「行」可归因（那边只有一整段表达式）。 */
    @Test
    void aBrokenExpressionReportsTheErrorAndNoRows() {
        Map<String, Object> draft = Map.of(
                "mode", "EXPRESSION",
                "expression", "{ \"qty\": $number($s_1.productCode) }");

        MappingTrialResult result = service.trial(new MappingTrialRequest(draft, erpBomJson(), 1));

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(result.failures()).isEmpty();
    }

    /** 编译不过的草稿跑不起来：说清为什么，不假装算出来一份报文。 */
    @Test
    void aDraftThatCannotCompileReportsWhy() {
        Map<String, Object> draft = Map.of("rules", List.of(
                Map.of("sourceStep", 1, "source", "productCode", "target", "", "transform", "STRING")));

        MappingTrialResult result = service.trial(new MappingTrialRequest(draft, erpBomJson(), 1));

        assertThat(result.ran()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.error()).contains("目标字段");
        assertThat(result.failures()).isEmpty();
    }

    /** 没有样例就没有可跑的数据：给一句能照着做的说法，而不是算出一堆 null 冒充结果。 */
    @Test
    void withoutASampleThereIsNothingToRun() {
        MappingTrialResult result = service.trial(new MappingTrialRequest(bomDraft(), null, 1));

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("还没有可试算的来源样例");
    }

    /**
     * 试算什么都不改（#116 验收八）：请求里那份草稿跑完还是原来那份，输出也不是它本身。
     *
     * <p>这个接口只读、无状态是结构上的事（它连数据库都没有），这一条钉的是「它没有顺手改手里的东西」
     * ——草稿那份 Map 是调用方（控制器）的对象，改它会让别的地方看见一份被算过的配置。</p>
     */
    @Test
    void theDraftIsNotTouchedByTheTrial() {
        Map<String, Object> draft = new LinkedHashMap<>(bomDraft());
        Map<String, Object> before = MAPPER.convertValue(draft, new TypeReference<>() {
        });

        service.trial(new MappingTrialRequest(draft, erpBomJson(), 1));

        assertThat(draft).isEqualTo(before);
    }

    // ---------- fixture：制品 → BOM 的草稿与真实 ERP 样例 ----------

    /**
     * 制品 → BOM：表头三条 + 数组（四条子行），与草稿校验那份 fixture 同一份契约。
     *
     * <p>顶层规则显式写着 {@code sourceStep=1}（编辑器产出的都这样，后端认「没写 = 任务输入 0」），
     * 组内子规则不写（继承组节点）。</p>
     */
    private static Map<String, Object> bomDraft() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sourceStep", 1);
        line.put("source", "items");
        line.put("target", "bom.bomLines");
        line.put("transform", "STRING");
        line.put("iterate", "FOR_EACH");
        line.put("children", List.of(
                childLeaf("lineNo", "lineNo", "INDEX"),
                childLeaf("materialCode", "materialCode", "STRING"),
                childInt("quantity", "qty"),
                childEnumUnit()));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("rules", List.of(
                concatBomNo(),
                topLeaf("productCode", "bom.productCode", "STRING"),
                topLeaf("productName", "bom.productName", "STRING"),
                line));
        return draft;
    }

    private static Map<String, Object> concatBomNo() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("sourceStep", 1);
        rule.put("source", "productCode");
        rule.put("target", "bom.bomNo");
        rule.put("transform", "CONCAT");
        rule.put("parts", List.of("BOM-", Map.of("path", "productCode")));
        return rule;
    }

    /** 顶层规则（来源步骤显式写着 1）。 */
    private static Map<String, Object> topLeaf(String source, String target, String transform) {
        Map<String, Object> rule = rule(source, target, transform);
        rule.put("sourceStep", 1);
        return rule;
    }

    /** 组内子规则：不写 sourceStep（继承组节点）。 */
    private static Map<String, Object> childLeaf(String source, String target, String transform) {
        return rule(source, target, transform);
    }

    private static Map<String, Object> childInt(String source, String target) {
        return rule(source, target, "INT");
    }

    private static Map<String, Object> childEnumUnit() {
        Map<String, Object> rule = rule("unit", "unit", "ENUM");
        rule.put("enumMap", Map.of("PCS", "件", "SET", "套"));
        return rule;
    }

    private static Map<String, Object> rule(String source, String target, String transform) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("source", source);
        rule.put("target", target);
        rule.put("transform", transform);
        return rule;
    }

    /** 真实 ERP BOM 样例（`erpBom` 那一层）的原文——编辑器左边那份样例的原文就是这个形状。 */
    private static String erpBomJson() {
        return MAPPER.valueToTree(erpBom()).toString();
    }

    /** 同一份样例，只把第 2 条明细的数量改成文本：`$number` 到它就抛。 */
    private static String erpBomJsonWithTextQuantity() {
        Map<String, Object> bom = erpBom();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) bom.get("items");
        List<Map<String, Object>> broken = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>(items.get(i));
            if (i == 1) {
                item.put("quantity", "abc");
            }
            broken.add(item);
        }
        bom.put("items", broken);
        return MAPPER.valueToTree(bom).toString();
    }

    private static Map<String, Object> erpBom() {
        try (InputStream in = MappingTrialServiceTest.class.getResourceAsStream("/json/erp测试数据.json")) {
            Map<String, Object> payload = MAPPER.readValue(in, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> bom = new LinkedHashMap<>((Map<String, Object>) payload.get("erpBom"));
            return bom;
        } catch (IOException e) {
            throw new UncheckedIOException("测试资源读不出来: /json/erp测试数据.json", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bomOf(MappingTrialResult result) {
        return (Map<String, Object>) ((Map<String, Object>) result.output()).get("bom");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> bomLinesOf(MappingTrialResult result) {
        return (List<Object>) bomOf(result).get("bomLines");
    }
}
