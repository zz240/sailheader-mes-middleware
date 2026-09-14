package com.example.edgegateway.service;

import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.web.dto.MappingDraftRequest;
import com.example.edgegateway.web.dto.MappingDraftValidation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「校验配置」那个只读接口的 fixture 驱动测试（#103）。
 *
 * <p>每组 fixture = 一份草稿 + 期望的问题集合 + 期望的编译产物文本。断言的全是这个接口的外部行为
 * （给一份草稿，返回哪些问题、编译产物是什么），不断言它内部调了哪个函数、以什么顺序。</p>
 *
 * <p>fixture 的输入与后端已有的契约测试同源：目标结构取 {@code docs/research/mapping-framework.md}
 * §4.1 的制品 → BOM，来源样例取 {@code erp测试数据.json}（{@code JsonataS0SpikeTest} 用的那份真实 ERP 样例）。</p>
 */
class MappingDraftValidationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MappingDraftValidationService service = new MappingDraftValidationService(MAPPER);

    /** 制品 → BOM（mapping-framework.md §4.1）：三层表头字段 + 一个数组。 */
    @Test
    void legalBomDraftHasNoProblems() throws Exception {
        MappingDraftValidation result = service.validate(request(bomDraft(), List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.compiled().jsonata()).isEqualTo("""
                {
                  "bom": {
                  "bomNo": "BOM-" & $s_1.productCode,
                  "productCode": $s_1.productCode,
                  "productName": $s_1.productName,
                  "bomLines": [ $map($s_1.items, function($g0, $i0) { {
                    "lineNo": $i0 + 1,
                    "materialCode": $g0.materialCode,
                    "materialName": $g0.materialName,
                    "qty": $number($g0.quantity),
                    "unit": $lookup({"PCS":"件","SET":"套"}, $g0.unit)
                  } }) ]
                  }
                }""");
    }

    // ---------- fixture 与共用形状 ----------

    /**
     * 结构有错的草稿：每条问题都定位到出错的那一行（目标字段 + 它所属的数组行）。
     *
     * <p>四类错各一条：叶子缺来源、数组子行缺来源（父行是数组行）、顶层用行号、sourceStep 指到自己；
     * 外加一条编译期才发现的（重复目标）——它说明编译产物那一份也参与了判断。</p>
     */
    @Test
    void structuralProblemsAreLocatedOnTheirRows() {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("sourceStep", 1);
        group.put("source", "items");
        group.put("target", "bom.bomLines");
        group.put("transform", "STRING");
        group.put("iterate", "FOR_EACH");
        group.put("children", List.of(leaf(null, null, "materialName")));

        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(1, "bomNo", "bom.bomNo"),
                leaf(2, "productCode", "bom.productCode"),
                leaf(1, null, "bom.productName"),
                group,
                leaf(1, "$index", "lineNo", "INDEX", Map.of("startAt", 1)),
                leaf(1, "productCode", "bom.bomNo")));

        MappingDraftValidation result = service.validate(request(draft, List.of(1), 1, 2));

        assertThat(problems(result.errors())).containsExactly(
                "bom.productName|null|映射规则缺少来源字段 source",
                "materialName|bom.bomLines|映射规则缺少来源字段 source",
                "lineNo|null|INDEX 转换只能在数组组内使用: lineNo",
                "bom.productCode|null|sourceStep=2 必须指向执行顺序更早的动作（当前序号 2）",
                "bom.bomNo|null|目标字段重复映射: bom.bomNo");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.valid()).isFalse();
        assertThat(result.compiled().jsonata()).isNull();
    }

    /**
     * 编译期才发现的问题也要带得走「它属于哪个数组行」：数组子行的目标字段写的是相对路径
     * （`qty`），光有它界面认不出是哪一行——顶层也可能有一个叫 `qty` 的行。
     */
    @Test
    void compileProblemsInsideAGroupCarryTheirArrayRow() {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("sourceStep", 1);
        group.put("source", "items");
        group.put("target", "bom.bomLines");
        group.put("transform", "STRING");
        group.put("iterate", "FOR_EACH");
        group.put("children", List.of(
                leaf(null, "quantity", "qty", "INT"),
                leaf(null, "lossRate", "qty", "INT")));

        MappingDraftValidation result = service.validate(request(Map.of("rules", List.of(group)), List.of(1), 1, 2));

        assertThat(problems(result.errors())).containsExactly("qty|bom.bomLines|目标字段重复映射: qty");
    }

    /** sourceStep 的两条错法各报一条：指到自己/更晚，和指向链上没有的序号。 */
    @Test
    void sourceStepMustPointAtAnEarlierActionInTheChain() {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(2, "productCode", "bom.productCode"),
                leaf(1, "productName", "bom.productName")));

        MappingDraftValidation result = service.validate(request(draft, List.of(2), 2, 2));

        assertThat(problems(result.errors())).containsExactly(
                "bom.productCode|null|sourceStep=2 必须指向执行顺序更早的动作（当前序号 2）",
                "bom.productName|null|sourceStep=1 在动作链中不存在，请填上游动作序号");
    }

    /** 漏写 sourceStep 是提示不是错误：后端把它当成任务输入，查不出这是个错（#98 D10）。 */
    @Test
    void missingSourceStepIsAHintNotAnError() {
        Map<String, Object> draft = Map.of("rules", List.of(leaf(null, "bomNo", "bom.bomNo")));

        MappingDraftValidation result = service.validate(request(draft, List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(problems(result.warnings())).containsExactly(
                "bom.bomNo|null|顶层规则没写 sourceStep——后端会把「没写」当成任务输入（编号 0），不会报错"
                        + "；编辑器顶部选的默认来源是动作 1");
    }

    /** 编译产物文本：一个是编译出去执行的表达式，一个是后端按现在的规则读出来的那份 JSON。 */
    @Test
    void compiledArtifactsAreTheTextsBackendStoresAndRuns() {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(1, "bomNo", "bom.bomNo"),
                leaf(1, "createdTime", "bom.createdDate", "DATE", Map.of("datePattern", "yyyy-MM-dd"))));

        MappingDraftValidation result = service.validate(request(draft, List.of(1), 1, 2));

        assertThat(result.compiled().jsonata()).isEqualTo("""
                {
                  "bom": {
                  "bomNo": $s_1.bomNo,
                  "createdDate": $dateFormat($s_1.createdTime, "yyyy-MM-dd")
                  }
                }""");
        assertThat(result.compiled().rulesJson()).isEqualTo("""
                {
                  "rules" : [ {
                    "sourceStep" : 1,
                    "source" : "bomNo",
                    "target" : "bom.bomNo",
                    "transform" : "STRING"
                  }, {
                    "sourceStep" : 1,
                    "source" : "createdTime",
                    "target" : "bom.createdDate",
                    "transform" : "DATE",
                    "datePattern" : "yyyy-MM-dd"
                  } ]
                }""");
    }

    /** 手写表达式（mode=EXPRESSION）：没有规则树可查，编译产物就是那份表达式本身。 */
    @Test
    void expressionDraftSkipsTheRuleTree() {
        String expression = "$map($s_1.items, function($v, $i) { { \"lineNo\": $i + 1 } })";
        Map<String, Object> draft = Map.of("mode", "EXPRESSION", "expression", expression);

        MappingDraftValidation result = service.validate(request(draft, List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.compiled().jsonata()).isEqualTo(expression);
        // 规则 JSON 里 expression 是 JSON 字符串，引号是转义过的——这里只看那份配置确实是表达式模式
        assertThat(result.compiled().rulesJson())
                .contains("\"mode\" : \"EXPRESSION\"")
                .contains("\"rules\" : [ ]");
    }

    /** 连配置都读不出来的草稿：报一条，定位不到某一行；规则 JSON 那一页照样显示收到的那份草稿。 */
    @Test
    void unreadableDraftReportsOneProblemWithoutARow() {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(1, "productCode", "bom.bomNo", "NOT_A_TRANSFORM")));

        MappingDraftValidation result = service.validate(request(draft, List.of(1), 1, 2));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).target()).isNull();
        assertThat(result.errors().get(0).message()).contains("没法读成字段映射配置");
        assertThat(result.compiled().jsonata()).isNull();
        assertThat(result.compiled().rulesJson()).contains("NOT_A_TRANSFORM");
    }

    /** 问题列表的可读形状（`目标字段|父行|说明`），断言里一眼看得出问题落在哪一行。 */
    private static List<String> problems(List<MappingDraftValidation.Problem> problems) {
        return problems.stream()
                .map(problem -> problem.target() + "|" + problem.parent() + "|" + problem.message())
                .toList();
    }

    /**
     * 请求：草稿 + 这份草稿要用到的外部事实。
     *
     * <p>目标清单与来源样例本轮不参与判断（基于样例的那六条检查是下一张票 #104），这里照样带上——
     * 请求形状是接口契约，两边同源的数据也在这里先走通一遍。</p>
     */
    private MappingDraftRequest request(Map<String, Object> draft, List<Integer> earlierIndexes,
                                        Integer defaultSourceStep, Integer actionIndex) {
        return new MappingDraftRequest(draft, bomTargets(), erpSourceFields(),
                earlierIndexes, defaultSourceStep, actionIndex);
    }

    /** 制品 → BOM 的草稿（mapping-framework.md §4.3 的规则树，来源路径取 ERP 样例里真有的字段）。 */
    private static Map<String, Object> bomDraft() {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("sourceStep", 1);
        group.put("source", "items");
        group.put("target", "bom.bomLines");
        group.put("transform", "STRING");
        group.put("iterate", "FOR_EACH");
        group.put("children", List.of(
                leaf(null, "$index", "lineNo", "INDEX", Map.of("startAt", 1)),
                leaf(null, "materialCode", "materialCode"),
                leaf(null, "materialName", "materialName"),
                leaf(null, "quantity", "qty", "INT"),
                leaf(null, "unit", "unit", "ENUM", Map.of("enumMap", enumMap("PCS", "件", "SET", "套")))));

        Map<String, Object> bomNo = leaf(1, "productCode", "bom.bomNo", "CONCAT",
                Map.of("parts", List.of("BOM-", Map.of("path", "productCode"))));

        return Map.of("rules", List.of(
                bomNo,
                leaf(1, "productCode", "bom.productCode"),
                leaf(1, "productName", "bom.productName"),
                group));
    }

    /** 枚举表：键序固定（`Map.of` 的迭代顺序不保证，编译产物的文本断言要看顺序）。 */
    private static Map<String, Object> enumMap(String... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 一条规则的形状：给什么参数写什么键（没有的不写），和编辑器存出来的一样。 */
    private static Map<String, Object> leaf(Integer sourceStep, String source, String target) {
        return leaf(sourceStep, source, target, "STRING", Map.of());
    }

    @SafeVarargs
    private static Map<String, Object> leaf(Integer sourceStep, String source, String target,
                                            String transform, Map<String, Object>... extra) {
        Map<String, Object> rule = new LinkedHashMap<>();
        if (sourceStep != null) {
            rule.put("sourceStep", sourceStep);
        }
        if (source != null) {
            rule.put("source", source);
        }
        rule.put("target", target);
        rule.put("transform", transform);
        for (Map<String, Object> more : extra) {
            rule.putAll(more);
        }
        return rule;
    }

    /** 目标字段清单：制品 → BOM 的目标结构（数组字段带元素内的字段）。 */
    private static List<MappingDraftRequest.TargetFieldSpec> bomTargets() {
        return List.of(
                new MappingDraftRequest.TargetFieldSpec("bom.bomNo", "VARCHAR", true, null),
                new MappingDraftRequest.TargetFieldSpec("bom.productCode", "VARCHAR", true, null),
                new MappingDraftRequest.TargetFieldSpec("bom.productName", "VARCHAR", true, null),
                new MappingDraftRequest.TargetFieldSpec("bom.bomLines", "ARRAY", true, List.of(
                        new MappingDraftRequest.TargetFieldSpec("lineNo", "INT", true, null),
                        new MappingDraftRequest.TargetFieldSpec("materialCode", "VARCHAR", true, null),
                        new MappingDraftRequest.TargetFieldSpec("materialName", "VARCHAR", true, null),
                        new MappingDraftRequest.TargetFieldSpec("qty", "INT", true, null),
                        new MappingDraftRequest.TargetFieldSpec("unit", "VARCHAR", true, null))));
    }

    /**
     * 来源样例：真实 ERP BOM 样例经结构探测展开成的字段清单。
     * 编辑器打开时拿到的就是同一种东西（`FieldMappingEditorService.latestOutput` 也走这个探测）。
     */
    private List<SourceField> erpSourceFields() {
        try (InputStream in = getClass().getResourceAsStream("/json/erp测试数据.json")) {
            Map<String, Object> payload = MAPPER.readValue(in, new TypeReference<>() {
            });
            return new StructureProbeService(null, null).probeSample(payload.get("erpBom")).fields();
        } catch (IOException e) {
            throw new UncheckedIOException("测试资源读不出来: /json/erp测试数据.json", e);
        }
    }
}
