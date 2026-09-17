package com.example.edgegateway.service;

import com.example.edgegateway.web.dto.MappingDraftRequest.TargetFieldSpec;
import com.example.edgegateway.web.dto.MappingTrialRequest;
import com.example.edgegateway.web.dto.MappingTrialResult;
import com.example.edgegateway.web.dto.MappingTrialResult.FieldState;
import com.example.edgegateway.web.dto.MappingTrialResult.FieldStatus;
import com.example.edgegateway.web.dto.MappingTrialResult.Metrics;
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
 * 「试算」那个只读接口的 fixture 驱动测试（#116 / #120）。
 *
 * <p>断言的全是外部行为：给一份草稿 + 一份来源样例，返回的目标报文是什么、没跑通时列出哪几行、
 * 逐字段的那张状态表把每一格定成哪一档。不断言它内部先跑了什么、后跑了什么。</p>
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
        MappingTrialResult result = service.trial(request(bomDraft(), erpBomJson(), 1, bomTargets()));

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
                request(bomDraft(), erpBomJsonWithTextQuantity(), 1, bomTargets()));

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

        MappingTrialResult result = service.trial(request(draft, erpBomJson(), 1, null));

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

        MappingTrialResult result = service.trial(request(bomDraft(), array, 1, bomTargets()));

        assertThat(result.ran()).isTrue();
        assertThat(bomOf(result).get("productCode")).isEqualTo("PRD-BUMPER-001");
        assertThat(bomLinesOf(result)).hasSize(2);
    }

    /** 手写 JSONata 也能试算（#116 验收六）：那一整段就是这次映射要执行的东西。 */
    @Test
    void anExpressionDraftCanBeTrialRunToo() {
        MappingTrialResult result = service.trial(request(expressionDraft(), erpBomJson(), 1, null));

        assertThat(result.ran()).isTrue();
        assertThat(result.output()).isEqualTo(Map.of("bomNo", "BOM-PRD-BUMPER-001", "items", 2));
    }

    /** 手写表达式跑不通时：整份那句话给出来，没有「行」可归因（那边只有一整段表达式）。 */
    @Test
    void aBrokenExpressionReportsTheErrorAndNoRows() {
        Map<String, Object> draft = Map.of(
                "mode", "EXPRESSION",
                "expression", "{ \"qty\": $number($s_1.productCode) }");

        MappingTrialResult result = service.trial(request(draft, erpBomJson(), 1, null));

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(result.failures()).isEmpty();
    }

    /** 编译不过的草稿跑不起来：说清为什么，不假装算出来一份报文。 */
    @Test
    void aDraftThatCannotCompileReportsWhy() {
        Map<String, Object> draft = Map.of("rules", List.of(
                Map.of("sourceStep", 1, "source", "productCode", "target", "", "transform", "STRING")));

        MappingTrialResult result = service.trial(request(draft, erpBomJson(), 1, null));

        assertThat(result.ran()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.error()).contains("目标字段");
        assertThat(result.failures()).isEmpty();
    }

    /** 没有样例就没有可跑的数据：给一句能照着做的说法，而不是算出一堆 null 冒充结果。 */
    @Test
    void withoutASampleThereIsNothingToRun() {
        MappingTrialResult result = service.trial(request(bomDraft(), null, 1, bomTargets()));

        assertThat(result.ran()).isFalse();
        assertThat(result.error()).contains("还没有可试算的来源样例");
    }

    // ---------- 逐字段状态表（#120） ----------

    /**
     * 整份跑通时照样给这张表（票里点名：不能只在没跑通时才填），而且数组那一组要交代清楚：
     * 组行给「落了几行」，每个元素字段各占一行、取值按元素分开给（#120 验收七）。
     */
    @Test
    void aRunnableDraftGivesEveryTargetFieldAStatusRow() {
        MappingTrialResult result = service.trial(request(bomDraft(), erpBomJson(), 1, bomTargets()));

        assertThat(result.fields()).extracting(FieldStatus::target)
                .containsExactly("bom.bomNo", "bom.productCode", "bom.productName", "bom.bomLines",
                        "lineNo", "materialCode", "qty", "unit");
        assertThat(result.fields()).allSatisfy(field -> assertThat(field.status()).isEqualTo(FieldState.VALUED));
        assertThat(result.fields()).allSatisfy(field -> assertThat(field.reason()).isNull());

        // 顶层字段给的是那个值本身，parent 是 null
        assertThat(fieldOf(result, "bom.productName").value()).isEqualTo("前保险杠");
        assertThat(fieldOf(result, "bom.productName").parent()).isNull();

        // 数组组行：不给值，给行数
        FieldStatus group = fieldOf(result, "bom.bomLines");
        assertThat(group.arrayRows()).isEqualTo(2);
        assertThat(group.value()).isNull();

        // 元素字段：值按元素分开给，parent 指向它所属的数组行
        assertThat(fieldOf(result, "materialCode").value()).isEqualTo(List.of("MAT-718H", "MAT-SKD61"));
        assertThat(fieldOf(result, "materialCode").parent()).isEqualTo("bom.bomLines");
        assertThat(((List<?>) fieldOf(result, "lineNo").value())).hasSize(2);

        // 计量条算术闭合：落值 + 省略 + 未映射 = 目标叶子字段总数（组行不算一个字段）
        Metrics metrics = result.metrics();
        assertThat(metrics.targets()).isEqualTo(7);
        assertThat(metrics.valued()).isEqualTo(7);
        assertThat(metrics.arrayRows()).isEqualTo(2);
        assertThat(metrics.valued() + metrics.dropped() + metrics.unmapped()).isEqualTo(metrics.targets());
    }

    /**
     * 数组只落了一行时，元素字段的取值**照样是一组**（长度 1），不能退化成标量。
     *
     * <p>界面上靠「值是不是数组」认出「按元素分开给」那一档（`trial.ts` 的 `Array.isArray`）——
     * 只有一个元素时退化成标量，那一格就跟一个顶层字段长得一模一样了。</p>
     */
    @Test
    void anArrayWithASingleRowStillGivesItsElementValuesAsAList() {
        Map<String, Object> bom = erpBom();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) bom.get("items");
        bom.put("items", List.of(items.get(0)));

        MappingTrialResult result = service.trial(
                request(bomDraft(), MAPPER.valueToTree(bom).toString(), 1, bomTargets()));

        assertThat(fieldOf(result, "materialCode").value()).isInstanceOf(List.class);
        assertThat(fieldOf(result, "materialCode").value()).isEqualTo(List.of("MAT-718H"));
        assertThat(fieldOf(result, "bom.bomLines").arrayRows()).isEqualTo(1);
        assertThat(result.metrics().arrayRows()).isEqualTo(1);
    }

    /**
     * 配了、样例里也没这个字段：键被引擎丢掉，这一格是 {@code DROPPED}——不是 {@code UNMAPPED}
     * （那是「草稿里压根没这条规则」，两件事在报文里长得一样，只有这张表分得开）。
     */
    @Test
    void aFieldTheSampleCannotProvideIsDroppedAndSaysWhy() {
        Object sample = erpBom();
        @SuppressWarnings("unchecked")
        Map<String, Object> bom = (Map<String, Object>) sample;
        bom.remove("productName");

        MappingTrialResult result = service.trial(request(bomDraft(), MAPPER.valueToTree(bom).toString(), 1, bomTargets()));

        FieldStatus productName = fieldOf(result, "bom.productName");
        assertThat(productName.status()).isEqualTo(FieldState.DROPPED);
        assertThat(productName.reason()).isEqualTo("来源 productName 在这份样例里取不到值，字段被丢掉了");
        assertThat(result.metrics().dropped()).isEqualTo(1);
        assertThat(result.metrics().valued() + result.metrics().dropped() + result.metrics().unmapped())
                .isEqualTo(result.metrics().targets());
    }

    /** 枚举表查不到键：同样是「键整个消失」，理由要与配置期校验那条警告同一个口径，并说出那个值。 */
    @Test
    void anEnumKeyThatIsNotInTheTableIsDropped() {
        MappingTrialResult result = service.trial(request(enumGapDraft(), erpBomJson(), 1, bomTargets()));

        FieldStatus unit = fieldOf(result, "unit");
        assertThat(unit.status()).isEqualTo(FieldState.DROPPED);
        assertThat(unit.reason()).contains("枚举表没有覆盖样例里出现过的值: PCS")
                .contains("查不到键的来源值不会出现在目标报文里：这个字段整个消失");
        assertThat(unit.parent()).isEqualTo("bom.bomLines");
    }

    /**
     * 键在结果里、值就是 null：这是**算出来就是 null**，必须与 {@code DROPPED} 分开（#120 的核心）。
     *
     * <p>样例里 {@code productName} 是显式的 null：引擎照旧把键写进报文，写的是 null——
     * 而它一旦取不到（见上一条），键会整个消失。界面上这两件事长得一模一样，只有这张表分得开。</p>
     */
    @Test
    void aFieldThatComesOutNullIsNullAndNotDropped() {
        Object sample = erpBom();
        @SuppressWarnings("unchecked")
        Map<String, Object> bom = (Map<String, Object>) sample;
        bom.put("productName", null);

        MappingTrialResult result = service.trial(request(bomDraft(), MAPPER.valueToTree(bom).toString(), 1, bomTargets()));

        FieldStatus productName = fieldOf(result, "bom.productName");
        assertThat(productName.status()).isEqualTo(FieldState.NULL);
        assertThat(productName.value()).isNull();
        assertThat(productName.reason()).isNull();
        assertThat(result.metrics().dropped()).isZero();
    }

    /**
     * 清单里有、草稿里没配：只有拿目标清单比才知道（#120 验收六）。
     *
     * <p>不带清单时这一档一律不判（票里的「可空」那一条）——没清单就没法说「清单里有 5 个、你配了 4 个」，
     * 这时那一格连行都不占（报文里也没有它）。</p>
     */
    @Test
    void aTargetWithNoRuleIsUnmappedOnlyWhenTheListIsGiven() {
        List<TargetFieldSpec> withExtra = new ArrayList<>(bomTargets());
        Map<String, Object> draft = bomDraft();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = new ArrayList<>((List<Map<String, Object>>) draft.get("rules"));
        withExtra.add(1, new TargetFieldSpec("bom.vendorName", "STRING", true, null));

        MappingTrialResult withList = service.trial(request(draft, erpBomJson(), 1, withExtra));
        FieldStatus vendor = fieldOf(withList, "bom.vendorName");
        assertThat(vendor.status()).isEqualTo(FieldState.UNMAPPED);
        assertThat(vendor.value()).isNull();
        assertThat(vendor.reason()).isNull();
        assertThat(vendor.parent()).isNull();
        assertThat(withList.metrics().unmapped()).isEqualTo(1);
        assertThat(withList.metrics().targets()).isEqualTo(8);
        assertThat(withList.metrics().valued() + withList.metrics().dropped() + withList.metrics().unmapped())
                .isEqualTo(withList.metrics().targets());

        MappingTrialResult withoutList = service.trial(request(draft, erpBomJson(), 1, null));
        assertThat(withoutList.fields()).extracting(FieldStatus::status)
                .doesNotContain(FieldState.UNMAPPED);
        assertThat(withoutList.metrics().unmapped()).isZero();
        assertThat(rules).hasSize(4); // 清单多一个字段不会顺手改草稿
    }

    /**
     * 手写表达式那一边没有「行」：{@code fields} 按表达式**实际产出的键**给，{@code UNMAPPED} 不判
     * ——哪怕请求里带着一份目标清单（那张清单是逐行那边的输入，这边没有行可比）。
     */
    @Test
    void anExpressionDraftReportsTheKeysItActuallyProduced() {
        MappingTrialResult result = service.trial(request(expressionDraft(), erpBomJson(), 1, bomTargets()));

        assertThat(result.fields()).extracting(FieldStatus::target).containsExactly("bomNo", "items");
        assertThat(result.fields()).allSatisfy(field -> assertThat(field.status()).isEqualTo(FieldState.VALUED));
        assertThat(result.fields()).extracting(FieldStatus::status).doesNotContain(FieldState.UNMAPPED);
        assertThat(result.metrics().targets()).isEqualTo(2);
        assertThat(result.metrics().unmapped()).isZero();
    }

    /** 整份没跑通时没有报文可对：这张表是空的、数也是零——不拿一份不存在的报文编出几档状态。 */
    @Test
    void aRunThatFailsAsAWholeHasNoFieldRows() {
        MappingTrialResult result = service.trial(
                request(bomDraft(), erpBomJsonWithTextQuantity(), 1, bomTargets()));

        assertThat(result.fields()).isEmpty();
        assertThat(result.metrics()).isEqualTo(Metrics.none());
        assertThat(result.failures()).isNotEmpty();
    }

    /**
     * 试算什么都不改（#116 验收八）：请求里那份草稿跑完还是原来那份，输出也不是它本身。
     *
     * <p>这个接口只读、无状态是结构上的事（它连数据库都没有），这一条钉的是「它没有顺手改手里的东西」
     * ——草稿那份 Map 是调用方（控制器）的对象，改它会让别的地方看见一份被算过的配置。
     * 逐字段那一层要反过来读草稿里的规则，同样一个字都不许写。</p>
     */
    @Test
    void theDraftIsNotTouchedByTheTrial() {
        Map<String, Object> draft = new LinkedHashMap<>(bomDraft());
        Map<String, Object> before = MAPPER.convertValue(draft, new TypeReference<>() {
        });

        service.trial(request(draft, erpBomJson(), 1, bomTargets()));

        assertThat(draft).isEqualTo(before);
    }

    // ---------- fixture：制品 → BOM 的草稿与真实 ERP 样例 ----------

    /** 一跑试算（#120 起多带一份目标清单）。 */
    private static MappingTrialRequest request(Map<String, Object> draft, String sampleJson, Integer step,
                                               List<TargetFieldSpec> targets) {
        return new MappingTrialRequest(draft, sampleJson, step, targets);
    }

    /**
     * 制品 → BOM：表头三条 + 数组（四条子行），与草稿校验那份 fixture 同一份契约。
     *
     * <p>顶层规则显式写着 {@code sourceStep=1}（编辑器产出的都这样，后端认「没写 = 任务输入 0」），
     * 组内子规则不写（继承组节点）。</p>
     */
    private static Map<String, Object> bomDraft() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("rules", List.of(
                concatBomNo(),
                topLeaf("productCode", "bom.productCode", "STRING"),
                topLeaf("productName", "bom.productName", "STRING"),
                bomLinesGroup(enumUnit(Map.of("PCS", "件", "SET", "套")))));
        return draft;
    }

    /** 同一份草稿，只把单位那一行的枚举表挖掉一个键：样例里的 `PCS` 查不到，字段整个消失。 */
    private static Map<String, Object> enumGapDraft() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("rules", List.of(
                concatBomNo(),
                topLeaf("productCode", "bom.productCode", "STRING"),
                topLeaf("productName", "bom.productName", "STRING"),
                bomLinesGroup(enumUnit(Map.of("SET", "套")))));
        return draft;
    }

    private static Map<String, Object> expressionDraft() {
        return Map.of(
                "mode", "EXPRESSION",
                "expression", "{ \"bomNo\": \"BOM-\" & $s_1.productCode, \"items\": $count($s_1.items) }");
    }

    private static Map<String, Object> bomLinesGroup(Map<String, Object> unitRule) {
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
                unitRule));
        return line;
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

    private static Map<String, Object> enumUnit(Map<String, Object> enumMap) {
        Map<String, Object> rule = rule("unit", "unit", "ENUM");
        rule.put("enumMap", enumMap);
        return rule;
    }

    private static Map<String, Object> rule(String source, String target, String transform) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("source", source);
        rule.put("target", target);
        rule.put("transform", transform);
        return rule;
    }

    /**
     * 目标字段清单（写目标那张表探出来的那一份）：表头三个 + 一个数组，数组元素四个。
     *
     * <p>数组元素写**相对路径**，与「校验配置」那份清单同一个形状。</p>
     */
    private static List<TargetFieldSpec> bomTargets() {
        return List.of(
                new TargetFieldSpec("bom.bomNo", "STRING", true, null),
                new TargetFieldSpec("bom.productCode", "STRING", true, null),
                new TargetFieldSpec("bom.productName", "STRING", false, null),
                new TargetFieldSpec("bom.bomLines", "ARRAY", true, List.of(
                        new TargetFieldSpec("lineNo", "INT", true, null),
                        new TargetFieldSpec("materialCode", "STRING", true, null),
                        new TargetFieldSpec("qty", "DECIMAL", true, null),
                        new TargetFieldSpec("unit", "STRING", false, null))));
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

    /** 状态表里的那一格（按目标字段找；数组元素字段写的是相对路径）。 */
    private static FieldStatus fieldOf(MappingTrialResult result, String target) {
        return result.fields().stream()
                .filter(field -> target.equals(field.target()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("状态表里没有这一格: " + target
                        + "（有这些：" + result.fields().stream().map(FieldStatus::target).toList() + "）"));
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
