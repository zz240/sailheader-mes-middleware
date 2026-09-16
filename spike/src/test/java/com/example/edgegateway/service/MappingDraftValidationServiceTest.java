package com.example.edgegateway.service;

import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.web.dto.MappingDraftRequest;
import com.example.edgegateway.web.dto.MappingDraftValidation;
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
 * 「校验配置」那个只读接口的 fixture 驱动测试（#103 起，#104 补上依赖清单与样例的六条检查）。
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
        MappingDraftValidation result = service.validate(
                request(bomDraft(), bomTargets(), List.of(1), 1, 2));

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

    // ---------- 依赖目标清单与来源样例的检查（#104） ----------

    /**
     * 必填的目标字段没有配置来源：拿**请求里带的目标清单**比草稿，不看样例。
     *
     * <p>界面上「还没选来源的空行不进草稿」（`configFromRows` 只留配好的行），所以这一条报的正是
     * 「清单里写着必填、用户却没配」——这类字段在运行时整个消失，也不报错（#98 问题陈述）。</p>
     *
     * <p><b>它是警告不是错误</b>（#113 / ADR-0012 决策 4）：它说的是目标清单的事实（这个必填字段现在是空的），
     * 不是「这份配置后端会拒」——后端从来不拦这种。清除掉一个必填字段的行之后，保存也不再被它拦下；
     * 后端仍然报得出它，是因为那一行不进草稿、清单里的必填字段照样没着落（#113 验收四、五）。</p>
     */
    @Test
    void requiredTargetFieldWithoutAMappingIsAWarning() {
        // 这份清单比 bomDraft() 多一个必填的 bom.vendorName：它就是这一组里「会触发」的那份草稿
        MappingDraftValidation result = service.validate(
                request(bomDraft(), fullBomTargets(), List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(problems(result.warnings()))
                .contains("bom.vendorName|null|必填的目标字段没有配置来源: bom.vendorName");
    }

    /** 对照：清单里的必填字段都配了，这一条就一条都不报（可选字段没配也不算）。 */
    @Test
    void configuredRequiredTargetsAreNotReported() {
        // bomTargets() 里只有 bom.bomLines 下的 qty / unit 是可选；其余必填的都在 bomDraft() 里配了
        MappingDraftValidation result = service.validate(
                request(bomDraft(), bomTargets(), List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    /**
     * 数组字段整行都没配时只报数组一行：它的元素字段一条也进不来，逐个报只会刷屏（#113 验收六）。
     *
     * <p>断言收窄到「必填没配」那一类上、而且是 `containsExactly`：整份警告列表里还有日期那条提示之类的
     * 噪声，但**元素字段一条都不许冒出来**正是这一条要钉住的事。</p>
     */
    @Test
    void anUnconfiguredRequiredArrayIsReportedOnce() {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(1, "productCode", "bom.bomNo"),
                leaf(1, "productCode", "bom.productCode"),
                leaf(1, "productName", "bom.productName")));

        MappingDraftValidation result = service.validate(request(draft, bomTargets(), List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(missingRequiredWarnings(result))
                .containsExactly("bom.bomLines|null|必填的目标字段没有配置来源: bom.bomLines");
    }

    /** 数组配了、只是某个必填子字段没配：逐条报子字段（带上它所属的数组行），别的元素字段不跟着报。 */
    @Test
    void anUnconfiguredRequiredArrayElementIsReportedOnItsOwnRow() {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(1, "productCode", "bom.bomNo"),
                leaf(1, "productCode", "bom.productCode"),
                leaf(1, "productName", "bom.productName"),
                groupWith(leaf(null, "materialCode", "materialCode"))));

        MappingDraftValidation result = service.validate(request(draft, bomTargets(), List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(missingRequiredWarnings(result))
                .containsExactly("materialName|bom.bomLines|必填的目标字段没有配置来源: materialName");
    }

    /**
     * 「必填没配来源」这条警告说的是**这条规则的目标字段**，子行写的是相对路径（#113 走查）。
     *
     * <p>界面上被清掉的那一行由编辑器自己给同一条提醒（`fast-config.ts` 的 `clearedRequiredNotices`），
     * 措辞必须与这里**逐字一致**：两边挂的是同一行、说的是同一件事，一边写完整路径
     * （`bom.bomLines.qty`）一边写相对路径（`qty`），点一次「校验配置」行上的话就变了。</p>
     */
    @Test
    void theMissingRequiredWarningForAnArrayChildNamesTheRelativePath() {
        List<MappingDraftRequest.TargetFieldSpec> targets = spec(arraySpec("bom.bomLines",
                elementSpec("qty", "INT", true), elementSpec("materialCode", "VARCHAR", false)));

        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(groupWith(leaf(null, "materialCode", "materialCode")))), targets,
                List.of(1), 1, 2));

        assertThat(missingRequiredWarnings(result))
                .containsExactly("qty|bom.bomLines|必填的目标字段没有配置来源: qty");
    }

    /**
     * 只有警告的草稿**存得下去**（#113 验收二、四）。
     *
     * <p>端到端那一段（`ActionService.add` 收下它）在 {@code ActionServiceTest}；这里先钉住它的前提：
     * 一份「必填字段一条都没配」的草稿，服务这边一条错误都不报。</p>
     */
    @Test
    void aDraftWithOnlyWarningsHasNoErrors() {
        MappingDraftValidation result = service.validate(
                request(bomDraft(), fullBomTargets(), List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).isNotEmpty();
    }

    /**
     * 来源字段在样例里不存在：写错的路径运行时会让目标字段整个消失，所以要说出来。
     *
     * <p>但它是**警告**不是错误（#113 / ADR-0012 决策 4）：后端从来不拒这种配置，编辑器也不该替它拦——
     * 样例只是最近一次成功运行的取样，陈旧时会误伤（#113 验收一）。</p>
     *
     * <p>三种来源写法的路径口径各不一样，各看一条：数组子行按**相对路径**判断（样例里是
     * `items[0].materialCode`）、外层引用去掉 `$root` 按绝对路径判断、跨步骤引用按绝对路径判断。
     * 报出来的话里带上「按样例算出来的那个路径」——相对路径与 `$root` 只看写的那一串认不出重合。</p>
     */
    @Test
    void unknownSourcePathsAreWarnedAboutOnTheirRows() {
        assertThat(sampleProblems(unknownSourceProblems(
                Map.of("rules", List.of(leaf(1, "materialCodex", "bom.bomNo"))), spec(topSpec("bom.bomNo", "VARCHAR")))))
                .containsExactly("bom.bomNo|null|来源字段不在样例里: materialCodex"
                        + "（按样例算是 materialCodex，字段清单里没有它）");

        // 数组那一行的目标字段整行标成可选：这份草稿要看的只是子行的相对路径
        List<MappingDraftRequest.TargetFieldSpec> groupTargets = spec(
                elementSpec("bom.bomLines", "ARRAY", false,
                        elementSpec("materialName", "VARCHAR", false),
                        elementSpec("qty", "INT", false),
                        elementSpec("lineNo", "INT", false)));
        assertThat(sampleProblems(unknownSourceProblems(Map.of("rules", List.of(groupWith(
                leaf(null, "materialCodex", "materialName"),
                leaf(null, "$index", "lineNo", "INDEX", Map.of("startAt", 1))))), groupTargets)))
                .containsExactly("materialName|bom.bomLines|来源字段不在样例里: materialCodex"
                        + "（按样例算是 items.materialCodex，字段清单里没有它）");

        // 对照组：子行的相对路径 `quantity` 在样例里是 `items[0].quantity`，补上前缀就认得出来
        assertThat(sampleProblems(unknownSourceProblems(Map.of("rules", List.of(groupWith(
                leaf(null, "quantity", "qty", "INT"),
                leaf(null, "$index", "lineNo", "INDEX", Map.of("startAt", 1))))), groupTargets))).isEmpty();

        // 对照组：`$root` 引用外层表头字段，本身就是绝对路径，不该再补数组前缀；
        // 路径两边的数组下标（样例里的 `items[0].` 与手写的 `items[0].`）折成同一种形状再比
        assertThat(sampleProblems(unknownSourceProblems(
                Map.of("rules", List.of(leaf(1, "$root.items[0].quantity", "bom.bomNo"))),
                spec(topSpec("bom.bomNo", "VARCHAR"))))).isEmpty();

        assertThat(sampleProblems(unknownSourceProblems(
                Map.of("rules", List.of(leaf(1, "$root.materialCodex", "bom.bomNo"))),
                spec(topSpec("bom.bomNo", "VARCHAR")))))
                .containsExactly("bom.bomNo|null|来源字段不在样例里: $root.materialCodex"
                        + "（按样例算是 materialCodex，字段清单里没有它）");
    }

    /** 对照：三种写法都对得上样例时，一条都不报。 */
    @Test
    void knownSourcePathsAreNotReported() {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(1, "productCode", "bom.bomNo"),
                leaf(1, "$root.materialCode", "bom.productCode"),
                groupWith(leaf(null, "quantity", "qty", "INT"), leaf(null, "unit", "unit"))));

        assertThat(sampleProblems(unknownSourceProblems(draft, sourceCheckTargets()))).isEmpty();
    }

    /**
     * 跨步骤引用：来源路径只在**它自己那一步**的样例里查得到。
     *
     * <p>请求里只带一份来源样例（编辑器当前选中的那个默认来源步骤的探测；顶部一换默认来源，
     * `onDefaultStepChange` 只挪还没配来源的行，已配的行留在原步骤）。拿这份样例去判别的步骤，会把配好
     * 的行整片误报成「来源字段不在样例里」——这个提醒照旧只对「跟这份样例同一步骤」的规则说。</p>
     */
    @Test
    void sourcesFromAnotherStepAreNotJudgedAgainstThisSample() {
        // 样例是动作 1 的，这一行取的是动作 2 的输出：判不了，跳过
        MappingDraftValidation otherStep = service.validate(request(
                Map.of("rules", List.of(leaf(2, "orderNo", "bom.bomNo"))),
                spec(topSpec("bom.bomNo", "VARCHAR")), List.of(source("materialCode", "STRING")),
                1, List.of(1, 2), 1, 3));

        assertThat(ruleProblems(otherStep.errors())).isEmpty();
        assertThat(sampleProblems(ruleProblems(otherStep.warnings()))).isEmpty();

        // 同一步里的错路径照常报（放宽的只是「别的步骤」，不是这条检查本身）
        assertThat(sampleProblems(unknownSourceProblems(
                Map.of("rules", List.of(leaf(1, "materialCodex", "bom.bomNo"))),
                spec(topSpec("bom.bomNo", "VARCHAR")))))
                .containsExactly("bom.bomNo|null|来源字段不在样例里: materialCodex"
                        + "（按样例算是 materialCodex，字段清单里没有它）");
    }

    /** 样例没说自己来自哪个动作时，「来源字段在不在样例里」这一条不判（判不了就别猜）。 */
    @Test
    void anUnlabelledSampleIsNotUsedToJudgeSourcePaths() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(leaf(1, "materialCodex", "bom.bomNo"))),
                spec(topSpec("bom.bomNo", "VARCHAR")), List.of(source("materialCode", "STRING")),
                null, List.of(1), 1, 2));

        assertThat(ruleProblems(result.errors())).isEmpty();
        assertThat(sampleProblems(ruleProblems(result.warnings()))).isEmpty();
    }

    /**
     * 数组行填来源数组：探测不会单独给出 `items` 这一条，只凭名字判会把每行正常的数组映射都报错。
     *
     * <p>清单里只有数组那一个字段——别的字段配没配来源是另一条检查的事。</p>
     */
    @Test
    void anArraySourceIsKnownWhenItsElementFieldsAreInTheSample() {
        List<MappingDraftRequest.TargetFieldSpec> arrayOnly = spec(
                elementSpec("bom.bomLines", "ARRAY", true,
                        elementSpec("qty", "INT", true), elementSpec("unit", "VARCHAR", true)));

        assertThat(sampleProblems(unknownSourceProblems(Map.of("rules", List.of(groupWith(
                leaf(null, "quantity", "qty", "INT"), leaf(null, "unit", "unit")))), arrayOnly))).isEmpty();
    }

    /**
     * 嵌套数组：前缀要一路累积下去（`items` → `items[0].parts` → `items[0].parts[0].code`），
     * 只补直接父级那一层会把合法的深层字段误报成「不在样例里」——而且是错误级，会拦住保存。
     */
    @Test
    void nestedArrayPathsAccumulateTheirPrefixes() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("source", "parts");
        inner.put("target", "lines"); // 子规则的目标写相对路径，前缀由外层那一组累积
        inner.put("transform", "STRING");
        inner.put("iterate", "FOR_EACH");
        inner.put("children", List.of(leaf(null, "code", "materialCode")));

        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("sourceStep", 1);
        outer.put("source", "items");
        outer.put("target", "bom.bomLines");
        outer.put("transform", "STRING");
        outer.put("iterate", "FOR_EACH");
        outer.put("children", List.of(inner));

        List<SourceField> sample = List.of(source("items[0].parts[0].code", "STRING"));
        List<MappingDraftRequest.TargetFieldSpec> targets = spec(arraySpec("bom.bomLines",
                elementSpec("lines", "ARRAY", true, elementSpec("materialCode", "VARCHAR", true))));

        MappingDraftValidation result = service.validate(request(Map.of("rules", List.of(outer)), targets, sample,
                List.of(1), 1, 2));

        assertThat(ruleProblems(result.errors())).isEmpty();
        assertThat(sampleProblems(ruleProblems(result.warnings()))).isEmpty();
    }

    /**
     * 「来源字段不在样例里」那一组的问题。
     *
     * <p>样例里只留素材单上的几个字段（`materialCode` / `materialName` / `productCode` /
     * `items[0].quantity` / `items[0].unit`）——`items` 这条数组自己**不在**清单里（结构探测把它展开
     * 成元素字段），所以数组行填 `items` 反而是对的：只要以它开头的元素字段在，这一条就不算错。</p>
     *
     * <p>只留这一条检查的问题：同一份 fixture 里别的检查（必填配没配）也会报，那些是它们各自
     * fixture 的事，混进来只会让这一组看不清。</p>
     */
    private List<FieldMappingConfig.RuleProblem> unknownSourceProblems(
            Map<String, Object> draft, List<MappingDraftRequest.TargetFieldSpec> targets) {
        MappingDraftValidation result = service.validate(request(draft, targets, List.of(
                source("materialCode", "STRING"), source("materialName", "STRING"), source("productCode", "STRING"),
                source("items[0].quantity", "NUMBER"), source("items[0].unit", "STRING")),
                List.of(1), 1, 2));
        return ruleProblems(result.warnings()).stream()
                .filter(problem -> problem.message().startsWith("来源字段不在样例里"))
                .toList();
    }

    /** 「来源字段不在样例里」那份样例里对得上的那几个目标字段（必填的都配了，别的都不提）。 */
    private static List<MappingDraftRequest.TargetFieldSpec> sourceCheckTargets() {
        return spec(
                topSpec("bom.bomNo", "VARCHAR"),
                topSpec("bom.productCode", "VARCHAR"),
                arraySpec("bom.bomLines",
                        elementSpec("qty", "INT", true), elementSpec("unit", "VARCHAR", true)));
    }

    /**
     * 枚举表没有覆盖样例里出现过的值：查不到键的来源值在目标报文里整个消失（不是变成 null），不报错，所以定成警告。
     *
     * <p>用的是来源字段**在这份样例里的实际值**（结构探测给的 samples），不试算表达式。</p>
     */
    @Test
    void enumValuesMissingFromTheTableAreWarnedAbout() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(enumerated("unit", "bom.bomNo", "PCS", "件"))),
                spec(topSpec("bom.bomNo", "VARCHAR")), enumSample(), List.of(1), 1, 2));

        assertThat(problems(result.warnings())).containsExactly(
                "bom.bomNo|null|枚举表没有覆盖样例里出现过的值: SET"
                        + "（查不到键的来源值不会出现在目标报文里：这个字段整个消失）");
        // 警告不拦保存：真正拦的是保存时那个 400、/validate、启用前链校验这三层
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    /** 对照：样例里出现过的值都在表里，一条都不报（表里多出来的键也不管）。 */
    @Test
    void enumValuesAllCoveredByTheTableAreNotWarnedAbout() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(enumerated("unit", "bom.bomNo", "PCS", "件", "SET", "套", "OTHER", "其它"))),
                spec(topSpec("bom.bomNo", "VARCHAR")), enumSample(), List.of(1), 1, 2));

        assertThat(result.warnings()).isEmpty();
    }

    /** 数组元素里的枚举：值的来源字段按相对路径补前缀（样例里是 `items[0].unit`）。 */
    @Test
    void enumInsideAnArrayUsesTheElementSampleValues() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(groupWith(
                        leaf(null, "unit", "unit", "ENUM", Map.of("enumMap", enumMap("PCS", "件")))))),
                spec(arraySpec("bom.bomLines", elementSpec("unit", "VARCHAR", true))), enumSample(), List.of(1), 1, 2));

        assertThat(problems(result.warnings())).containsExactly(
                "unit|bom.bomLines|枚举表没有覆盖样例里出现过的值: SET"
                        + "（查不到键的来源值不会出现在目标报文里：这个字段整个消失）");
    }

    /**
     * 样例里没有这个来源字段时枚举覆盖这条判不了。
     *
     * <p>这一行上照样有一条警告——「来源字段不在样例里」那条（#113 起它也是警告），不过那是另一条检查的
     * 事；这里只看枚举这条不补话。</p>
     */
    @Test
    void enumWithoutASourceSampleIsNotJudged() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(enumerated("noSuchField", "bom.bomNo", "PCS", "件"))),
                spec(topSpec("bom.bomNo", "VARCHAR")), enumSample(), List.of(1), 1, 2));

        assertThat(problems(result.warnings()))
                .containsExactly("bom.bomNo|null|来源字段不在样例里: noSuchField"
                        + "（按样例算是 noSuchField，字段清单里没有它）");
    }

    /**
     * 数组子行取「外层 · 」的字段时，枚举值同样按绝对路径查样例。
     *
     * <p>{@code $root.<路径>} 指这份来源数据的顶层，编译器也把它编成 {@code $s_<step>.<路径>}——所以值
     * 要去外层那个字段的样例里找，不是补数组前缀。</p>
     */
    @Test
    void enumThroughAnOuterReferenceUsesTheOuterSampleValues() {
        List<SourceField> sample = List.of(
                new SourceField("items[0].materialCode", "STRING", false, List.of("PCS")),
                new SourceField("materialCode", "STRING", false, List.of("PCS", "SET")));

        assertThat(problems(service.validate(request(
                Map.of("rules", List.of(groupWith(leaf(null, "$root.materialCode", "materialCode",
                        "ENUM", Map.of("enumMap", enumMap("PCS", "件")))))),
                spec(arraySpec("bom.bomLines", elementSpec("materialCode", "VARCHAR", true))),
                sample, List.of(1), 1, 2)).warnings()))
                .containsExactly("materialCode|bom.bomLines|枚举表没有覆盖样例里出现过的值: SET"
                        + "（查不到键的来源值不会出现在目标报文里：这个字段整个消失）");
    }

    /**
     * 文本值直接写进数字类型的字段：不套一层数字转换的话，运行到那一条会失败。
     *
     * <p>判断用的是目标清单里的类型与来源字段在样例里的类型，两边都得探到才判——探不到就不猜。</p>
     */
    @Test
    void textValueIntoANumberTargetIsWarnedAbout() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(leaf(1, "unit", "bom.qty"))),
                spec(topSpec("bom.qty", "INT")), numberSample(), List.of(1), 1, 2));

        assertThat(problems(result.warnings())).containsExactly(
                "bom.qty|null|目标字段 bom.qty 是数字类型（INT），来源字段 unit 是文本（STRING）"
                        + "——建议套一层数字转换");
        assertThat(result.errors()).isEmpty();
    }

    /** 对照：套了数字转换就没事——数字转换正是这条的解药。 */
    @Test
    void aNumberTransformIntoANumberTargetIsFine() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(leaf(1, "unit", "bom.qty", "INT"))),
                spec(topSpec("bom.qty", "INT")), numberSample(), List.of(1), 1, 2));

        assertThat(result.warnings()).isEmpty();
    }

    /** 对照：来源本来就是数字（样例里是 NUMBER）时也不用套转换。 */
    @Test
    void aNumberSourceIntoANumberTargetIsFine() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(leaf(1, "quantity", "bom.qty"))),
                spec(topSpec("bom.qty", "INT")), numberSample(), List.of(1), 1, 2));

        assertThat(result.warnings()).isEmpty();
    }

    /** 对照：目标是文本字段时随便映射（「文本写进数字字段」不该管到别的字段上）。 */
    @Test
    void aTextValueIntoATextTargetIsFine() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(leaf(1, "unit", "bom.unit"))),
                spec(topSpec("bom.unit", "VARCHAR")), numberSample(), List.of(1), 1, 2));

        assertThat(result.warnings()).isEmpty();
    }

    /** 常量是用户写死的值，是不是数字由那一行的字面量说了算，这条不猜（也不该拿它拦人）。 */
    @Test
    void aConstantIntoANumberTargetIsNotJudged() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(leaf(1, null, "bom.qty", "CONSTANT", Map.of("constant", "1")))),
                spec(topSpec("bom.qty", "INT")), numberSample(), List.of(1), 1, 2));

        assertThat(result.warnings()).isEmpty();
    }

    /** 数字类型的几种写法人话里说得出来：DECIMAL 也算数字（探到的列类型是库里的写法）。 */
    @Test
    void otherNumericColumnTypesAreRecognized() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(leaf(1, "unit", "bom.qty"))),
                spec(topSpec("bom.qty", "DECIMAL(10,2)")), numberSample(), List.of(1), 1, 2));

        assertThat(problems(result.warnings())).containsExactly(
                "bom.qty|null|目标字段 bom.qty 是数字类型（DECIMAL(10,2)），来源字段 unit 是文本（STRING）"
                        + "——建议套一层数字转换");
    }

    /** 数组元素里的数字字段同理：目标类型按「数组行的目标字段 + 子行的相对路径」查。 */
    @Test
    void aTextValueIntoANumberElementIsWarnedAbout() {
        MappingDraftValidation result = service.validate(request(
                Map.of("rules", List.of(groupWith(leaf(null, "unit", "qty")))),
                spec(arraySpec("bom.bomLines", elementSpec("qty", "INT", true))), numberSample(), List.of(1), 1, 2));

        assertThat(problems(result.warnings())).containsExactly(
                "qty|bom.bomLines|目标字段 bom.bomLines.qty 是数字类型（INT），来源字段 items[0].unit 是文本"
                        + "（STRING）——建议套一层数字转换");    }

    /**
     * 拼接里存在空片段：不会报错，但通常是无意留下的（编译出来是空字符串，字段看着像配了）。
     *
     * <p>空片段的两种写法各看一条：常量片段是空串，路径片段没写 `path`（或只有空白）。这一条只看
     * 草稿本身，不看目标清单也不看样例。</p>
     */
    @Test
    void emptyConcatPartsAreWarnedAbout() {
        assertThat(sampleProblems(concatWarnings(leaf(1, "productCode", "bom.bomNo", "CONCAT",
                Map.of("parts", List.of("BOM-", ""))))))
                .containsExactly("bom.bomNo|null|拼接里有 1 个空片段（这一行最后会拼出空字符串）");

        assertThat(sampleProblems(concatWarnings(leaf(1, "productCode", "bom.bomNo", "CONCAT",
                Map.of("parts", List.of(Map.of("path", "productCode"), Map.of("path", "  ")))))))
                .containsExactly("bom.bomNo|null|拼接里有 1 个空片段（这一行最后会拼出空字符串）");

        // 两个空片段一起报一条，说清有几个
        assertThat(sampleProblems(concatWarnings(leaf(1, "productCode", "bom.bomNo", "CONCAT",
                Map.of("parts", List.of("BOM-", "", Map.of("path", "")))))))
                .containsExactly("bom.bomNo|null|拼接里有 2 个空片段（这一行最后会拼出空字符串）");
    }

    /** 对照：片段都填了值就不报（这是编辑器里正常配出来的一行）。 */
    @Test
    void filledConcatPartsAreNotWarnedAbout() {
        Map<String, Object> draft = leaf(1, "productCode", "bom.bomNo", "CONCAT",
                Map.of("parts", List.of("BOM-", Map.of("path", "productCode"))));

        assertThat(concatWarnings(draft)).isEmpty();
    }

    /**
     * 片段转换（#112）：加工片段与行号片段都不是「空片段」——它们有内容，只是参数在别处。
     *
     * <p>这条守着的是「判空只看值那一格」：行号片段整个不带 `path`，照「没有 path 就算空」判会把每一行
     * 正常的行号片段都报成空片段。</p>
     */
    @Test
    void transformedPartsAreNotEmptyParts() {
        Map<String, Object> draft = leaf(1, null, "bom.bomNo", "CONCAT",
                Map.of("parts", List.of("LOT-",
                        Map.of("transform", "INDEX", "startAt", 1),
                        Map.of("path", "createdTime", "transform", "DATE", "datePattern", "yyyyMMdd"),
                        Map.of("path", "unit", "transform", "ENUM", "enumMap", Map.of("PCS", "件")))));

        assertThat(concatWarnings(draft)).isEmpty();
    }

    /**
     * 对象片段没写 `path` 不算那条警告：结构校验已经报了一条错误，再补一条说同一件事的警告只会把一条错
     * 说成两条（#112 起还带上「第几个片段」）。
     *
     * <p>编译器那边也认这种片段了：不认的话它会在 {@code m.get("path").toString()} 上抛 NPE，把整个接口
     * 打成 500——「校验配置」这条链路要的是「报一条问题」。结构与编译在同一个片段上报的是**同一条**
     * （消息逐字相同），服务那边合成一条（{@code MappingDraftValidationService.addOnce}）。</p>
     */
    @Test
    void aPathPartWithoutTheKeyIsStructuralNotAWarning() {
        Map<String, Object> rule = leaf(1, "productCode", "bom.bomNo", "CONCAT",
                Map.of("parts", List.of("BOM-", Map.of("nope", "productCode"))));

        MappingDraftValidation result = service.validate(draftOnly(Map.of("rules", List.of(rule))));

        assertThat(problems(result.errors())).containsExactly(
                "bom.bomNo|null|拼接第 2 个片段：缺少来源字段 path: bom.bomNo");
        assertThat(concatWarnings(rule)).isEmpty();
    }

    /** 数组子行里的拼接同理，报的话带上它所属的数组行。 */
    @Test
    void anEmptyConcatPartInsideAnArrayCarriesItsArrayRow() {
        Map<String, Object> draft = groupWith(
                leaf(null, "materialCode", "bomNo", "CONCAT", Map.of("parts", List.of("BOM-", ""))));

        assertThat(sampleProblems(concatWarnings(draft)))
                .containsExactly("bomNo|bom.bomLines|拼接里有 1 个空片段（这一行最后会拼出空字符串）");
    }

    /** 「拼接里有空片段」是警告，不是错误：不拦保存。 */
    @Test
    void emptyConcatPartsDoNotBlockSaving() {
        Map<String, Object> rule = leaf(1, "productCode", "bom.bomNo", "CONCAT",
                Map.of("parts", List.of("BOM-", "")));

        MappingDraftValidation result = service.validate(draftOnly(Map.of("rules", List.of(rule))));

        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    /**
     * 只看「拼接里空片段」那一条的问题（同一份草稿里别的检查也会报，那些是它们各自 fixture 的事）。
     *
     * <p>清单与样例都空着：这一条本来就只看草稿本身，别的检查在空清单上一条都不报——要断言的就只剩它。</p>
     */
    private List<FieldMappingConfig.RuleProblem> concatWarnings(Map<String, Object> draft) {
        return ruleProblems(service.validate(draftOnly(Map.of("rules", List.of(draft)))).warnings());
    }

    /** 只带草稿的那份请求：目标清单与来源样例都空着，只看草稿本身的那几条检查用它最干净。 */
    private static MappingDraftRequest draftOnly(Map<String, Object> draft) {
        // 草稿里的规则都写着 sourceStep=1，所以链上更早的动作就是 1（否则会多报一条 sourceStep 不在链上）
        return new MappingDraftRequest(draft, List.of(), List.of(), null, List.of(1), 1, 2);
    }

    /** 数组子行取「外层 · 」的字段时，类型也按绝对路径查样例（`$root.` 指顶层，不补数组前缀）。 */
    @Test
    void anOuterReferenceIntoANumberTargetIsWarnedAbout() {
        List<SourceField> sample = List.of(
                new SourceField("materialCode", "STRING", false, List.of("PCS")),
                new SourceField("items[0].materialCode", "STRING", false, List.of("PCS")));

        assertThat(problems(service.validate(request(
                Map.of("rules", List.of(groupWith(leaf(null, "$root.materialCode", "qty")))),
                spec(arraySpec("bom.bomLines", elementSpec("qty", "INT", true))),
                sample, List.of(1), 1, 2)).warnings()))
                .containsExactly("qty|bom.bomLines|目标字段 bom.bomLines.qty 是数字类型（INT），"
                        + "来源字段 materialCode 是文本（STRING）——建议套一层数字转换");
    }

    // ---------- 日期格式串（#111） ----------

    /**
     * 非法格式串是**错误**，保存前就被拦下（#111 验收）。
     *
     * <p>为什么必须在这里拦：{@code JsonataFunctions.formatDate} 的 {@code ofPattern} 写在所有 try 之外，
     * 非法格式串运行到这个转换时任务直接失败；而结构校验、编译、草稿校验三层以前一层都不查它
     * （ADR-0012 背景第五条）。</p>
     *
     * <p>两种「非法」各看一条：{@code ISO-8601} 是平台解析器直接拒的写法（Unknown pattern letter: I）；
     * {@code YYYY-MM-DD} 能过解析器，但 {@code Y} / {@code D} 的含义跟长相不是一回事（周所在年 / 一年中的
     * 第几天），算出来是 {@code 2026-08-233} 这种值——同样是写错了，一样拦。</p>
     */
    @Test
    void anIllegalDatePatternIsAnErrorThatBlocksSaving() {
        assertThat(datePatternErrors("ISO-8601")).containsExactly(
                "bom.createdDate|null|日期格式串不合法: ISO-8601（Unknown pattern letter: I）"
                        + "——运行到这个转换时任务会直接失败");

        assertThat(datePatternErrors("YYYY-MM-DD")).containsExactly(
                "bom.createdDate|null|日期格式串不合法: YYYY-MM-DD（Y 是「周所在年」不是年、D 是「一年中的第几天」"
                        + "不是日，拿 2026-08-21 试一下会写成 2026-08-233；年月日请写 yyyy-MM-dd）");
    }

    /** 对照：四个预设与常见的客户格式串都放行（这条校验只拦「写错了」，不拦「没见过的写法」）。 */
    @Test
    void legalDatePatternsAreAccepted() {
        for (String pattern : List.of("yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyyMMdd", "yyyy/MM/dd",
                "yyyyMMddHHmmss", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy年MM月dd日", "yyyy-MM")) {
            assertThat(datePatternErrors(pattern)).as(pattern).isEmpty();
        }
    }

    /**
     * 单引号里的字母是字面量，不是格式字母——`'D'` 与 `'T'` 都不该被当成陷阱拦掉。
     *
     * <p>反过来 `''D''`（两个单引号 = 一个转义的单引号，D 仍然是格式字母）**要**拦：实测那个格式串把
     * 2026-08-21 写成 `'233'`，D 真的是「一年中的第几天」。这一条把「引号怎么读」钉住，免得以后有人
     * 把陷阱字母的扫描改成「看见 D 就拦」或者「看见引号就整段跳过」。</p>
     */
    @Test
    void quotedLettersAreLiteralsNotPatternLetters() {
        for (String pattern : List.of("'D'", "'T'", "yyyy''MM''dd", "'a''D''b'", "yyyy-MM-dd'T'HH:mm:ss")) {
            assertThat(datePatternErrors(pattern)).as(pattern).isEmpty();
        }

        assertThat(datePatternErrors("''D''")).as("''D''")
                .containsExactly("bom.createdDate|null|日期格式串不合法: ''D''（D 是「一年中的第几天」不是日，"
                        + "拿 2026-08-21 试一下会写成 '233'；年月日请写 yyyy-MM-dd）");
    }

    /** 格式串整个没写（空串 / 只有空白 / 键都不在）不是错：编译器和运行时都用默认的 `yyyy-MM-dd`。 */
    @Test
    void aBlankDatePatternIsNotAnError() {
        for (String pattern : java.util.Arrays.asList("", "  ", null)) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("sourceStep", 1);
            rule.put("source", "createdTime");
            rule.put("target", "bom.createdDate");
            rule.put("transform", "DATE");
            if (pattern != null) {
                rule.put("datePattern", pattern);
            }

            MappingDraftValidation result = service.validate(new MappingDraftRequest(
                    Map.of("rules", List.of(rule)), dateTargets(), List.of(), 1, List.of(1), 1, 2));

            assertThat(problems(result.errors())).as("[" + pattern + "]").isEmpty();
        }
    }

    /**
     * 只看「日期格式串」那一条的问题。
     *
     * <p>目标清单收窄到这一条规则的目标字段、来源样例空着——同一份草稿里别的检查（必填配没配、来源在不在
     * 样例里）就没话可说，断言里只剩这一条。</p>
     */
    private List<String> datePatternErrors(String pattern) {
        return problems(service.validate(new MappingDraftRequest(
                dateDraft(pattern), dateTargets(), List.of(), 1, List.of(1), 1, 2)).errors());
    }

    /** 一条日期格式化规则（来源 `createdTime`）——日期格式串那几组 fixture 共用。 */
    private static Map<String, Object> dateDraft(String pattern) {
        return Map.of("rules", List.of(
                leaf(1, "createdTime", "bom.createdDate", "DATE", Map.of("datePattern", pattern))));
    }

    /** 日期格式串那一组的目标清单：只有它映射的那一个字段。 */
    private static List<MappingDraftRequest.TargetFieldSpec> dateTargets() {
        return spec(topSpec("bom.createdDate", "DATE"));
    }

    /**
     * 格式串在样例值上真跑一遍：跑不出来的给一条**警告**（#111 验收三）。
     *
     * <p>这正是「挑不到预设就只能整行走手写 JSONata」那条抱怨的正面：客户数据里 `createdTime` 只有日期
     * （`2026-08-21`），格式串却要时分秒——运行时的四次 parse 全失败后**原样发出去**，任务照样 SUCCESS，
     * 错在目标那边才看得见。样例在手时这里就能说出来。</p>
     */
    @Test
    void aDatePatternThatCannotFormatTheSampleValuesIsWarnedAbout() {
        MappingDraftValidation result = service.validate(new MappingDraftRequest(
                dateDraft("yyyyMMddHHmmss"), dateTargets(),
                List.of(source("createdTime", "STRING", "2026-08-21", "2026-08-22")),
                1, List.of(1), 1, 2));

        assertThat(problems(result.warnings())).containsExactly(
                "bom.createdDate|null|日期格式串 yyyyMMddHHmmss 在样例值上解析不出来: 2026-08-21、2026-08-22"
                        + "（运行时会原样发出去，不报错）");
        // 警告不拦保存：拦保存的是错误那一档（#111 验收三）
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    /** 对照：格式串跟样例值对得上时一条都不报（这条检查不是「见到日期就提醒」）。 */
    @Test
    void aDatePatternThatFormatsTheSampleValuesIsNotWarnedAbout() {
        // 带时分秒的时间戳 + 要时分秒的格式串：这正是客户数据常见的那个组合
        assertThat(datePatternWarnings(dateDraft("yyyyMMddHHmmss"),
                List.of(source("createdTime", "STRING", "2026-08-21T10:20:30Z")))).isEmpty();

        // 纯日期 + 纯日期的格式串（也是四个预设里的第一个）
        assertThat(datePatternWarnings(dateDraft("yyyy-MM-dd"),
                List.of(source("createdTime", "STRING", "2026-08-21")))).isEmpty();

        // 时间戳 + 只要日期：解析得出来，格式化也做得成
        assertThat(datePatternWarnings(dateDraft("yyyy-MM-dd"),
                List.of(source("createdTime", "STRING", "2026-08-21T10:20:30Z")))).isEmpty();
    }

    /** 数组子行里的日期转换：值的来源字段按相对路径补前缀（样例里是 `items[0].createdTime`）。 */
    @Test
    void aDatePatternInsideAnArrayUsesTheElementSampleValues() {
        Map<String, Object> draft = Map.of("rules", List.of(groupWith(
                leaf(null, "createdTime", "createdDate", "DATE", Map.of("datePattern", "yyyyMMddHHmmss")))));

        MappingDraftValidation result = service.validate(new MappingDraftRequest(draft,
                spec(arraySpec("bom.bomLines", elementSpec("createdDate", "DATE", true))),
                List.of(source("items[0].createdTime", "STRING", "2026-08-21")), 1, List.of(1), 1, 2));

        assertThat(problems(result.warnings())).containsExactly(
                "createdDate|bom.bomLines|日期格式串 yyyyMMddHHmmss 在样例值上解析不出来: 2026-08-21"
                        + "（运行时会原样发出去，不报错）");
    }

    /**
     * 没有样例可试时只给一句提示（#111 验收四）：不拦保存。
     *
     * <p>两种「没样例」：请求里根本没带样例；带的是**别的步骤**的样例（跨步骤引用、或顶部默认来源换过
     * 之后留在原步骤的那些行）——判不了就别猜，与「来源字段不在样例里」那条同一条口径。</p>
     */
    @Test
    void aDatePatternWithoutASampleIsOnlyAHint() {
        String hint = "日期格式串 yyyyMMddHHmmss 没在样例上试过（手上还没有这一步的来源样例）"
                + "——配错了要到目标系统那边才发现";

        assertThat(datePatternWarnings(dateDraft("yyyyMMddHHmmss"), List.of()))
                .containsExactly("bom.createdDate|null|" + hint);

        // 样例是动作 1 的，这一行取的是动作 2 的输出：这一份样例判不了它（同「来源字段在不在样例里」的口径）
        Map<String, Object> otherStep = Map.of("rules", List.of(
                leaf(2, "createdTime", "bom.createdDate", "DATE", Map.of("datePattern", "yyyyMMddHHmmss"))));
        assertThat(problems(service.validate(new MappingDraftRequest(otherStep,
                dateTargets(), List.of(source("createdTime", "STRING", "2026-08-21")), 1, List.of(1, 2), 1, 3))
                .warnings()))
                .containsExactly("bom.createdDate|null|" + hint);
    }

    /** 只有字段名、没有样例值（探测没取到样本）时同样试不了，给的还是那句提示——成因不同，话也说清。 */
    @Test
    void aDatePatternIsOnlyAHintWhenTheSampleHasNoValues() {
        assertThat(datePatternWarnings(dateDraft("yyyyMMddHHmmss"), List.of(source("createdTime", "STRING"))))
                .containsExactly("bom.createdDate|null|日期格式串 yyyyMMddHHmmss 没在样例上试过"
                        + "（样例里这个字段一条值都没取到）——配错了要到目标系统那边才发现");
    }

    /**
     * 这一行的来源字段压根不在样例里时不补这条提示：「来源字段不在样例里」那条已经在说这件事，
     * 同一行上报两条说的是同一件事。
     *
     * <p>那条**警告**本身是另一条检查的事（#113 起「来源不在样例里」也是警告）——这里只锁「日期这条不补话」，
     * 所以对着整个警告列表断言。</p>
     */
    @Test
    void aDatePatternIsNotTriedWhenItsSourceFieldIsNotInTheSample() {
        MappingDraftValidation result = service.validate(new MappingDraftRequest(dateDraft("yyyyMMddHHmmss"),
                dateTargets(), List.of(source("created_time", "STRING", "2026-08-21")), 1, List.of(1), 1, 2));

        assertThat(result.errors()).isEmpty();
        assertThat(problems(result.warnings())).containsExactly(
                "bom.createdDate|null|来源字段不在样例里: createdTime（按样例算是 createdTime，字段清单里没有它）");
    }

    /** 只看日期格式串那两条（在样例上试没试过）的警告。 */
    private List<String> datePatternWarnings(Map<String, Object> draft, List<SourceField> sample) {
        return problems(service.validate(new MappingDraftRequest(
                draft, dateTargets(), sample, 1, List.of(1), 1, 2)).warnings());
    }

    /**
     * 数组行（组节点）上写着日期转换与格式串时两条都不判。
     *
     * <p>组节点的 transform 不参与编译——编译器只看它的 children（`JsonataRuleCompiler.groupExpr`），
     * 结构校验也是先走组那几条就 `continue`。拿它报一条「没在样例上试过」等于报一件运行时根本不会
     * 发生的事。</p>
     */
    @Test
    void aDatePatternOnAGroupNodeIsNotJudged() {
        Map<String, Object> group = groupWith(leaf(null, "materialCode", "materialCode"));
        group.put("transform", "DATE");
        group.put("datePattern", "yyyyMMddHHmmss");

        MappingDraftValidation result = service.validate(new MappingDraftRequest(
                Map.of("rules", List.of(group)),
                spec(arraySpec("bom.bomLines", elementSpec("materialCode", "VARCHAR", true))),
                List.of(), 1, List.of(1), 1, 2));

        assertThat(result.warnings()).isEmpty();
    }

    // ---------- 片段转换（#112） ----------

    /**
     * 片段配错了要指到**哪一个片段**（#112 验收五）：序号从 1 数。
     *
     * <p>五种错法各一条：认不出的转换、缺 `path`、日期格式串非法、枚举表空、行号片段用在顶层拼接行上
     * （最后一条与行级 INDEX 同一条理由——`$index` 只在数组元素里有意义）。</p>
     */
    @Test
    void partProblemsPointAtTheOffendingPart() {
        assertThat(partProblems(Map.of("path", "bomNo", "transform", "NOT_A_TRANSFORM"))).containsExactly(
                "bom.bomNo|null|拼接第 1 个片段：认不出的片段转换 NOT_A_TRANSFORM: bom.bomNo");

        assertThat(partProblems(Map.of("transform", "DATE", "datePattern", "yyyyMMdd"))).containsExactly(
                "bom.bomNo|null|拼接第 1 个片段：缺少来源字段 path: bom.bomNo");

        assertThat(partProblems(Map.of("path", "createdTime", "transform", "DATE", "datePattern", "ISO-8601")))
                .containsExactly("bom.bomNo|null|拼接第 1 个片段：日期格式串不合法: ISO-8601"
                        + "（Unknown pattern letter: I）——运行到这个转换时任务会直接失败: bom.bomNo");

        assertThat(partProblems(Map.of("path", "unit", "transform", "ENUM"))).containsExactly(
                "bom.bomNo|null|拼接第 1 个片段：枚举转换需要 enumMap: bom.bomNo");

        assertThat(partProblems(Map.of("transform", "INDEX", "startAt", 1))).containsExactly(
                "bom.bomNo|null|拼接第 1 个片段：行号片段只能在数组映射的子行里用: bom.bomNo");
    }

    /** 片段整个不是字符串也不是对象时照样指到那一个片段（老消息换成带序号的那一句）。 */
    @Test
    void anUnreadablePartPointsAtItsIndex() {
        assertThat(partProblems(42)).containsExactly(
                "bom.bomNo|null|拼接第 1 个片段：片段只能是字符串常量或一个对象: bom.bomNo");
    }

    /**
     * 六种片段（常量 + 五种转换）都配对了时一条错都不报，编译产物还是那段 `&` 串。
     *
     * <p>这就是「每个片段各自挂一个转换」的正常形态。</p>
     */
    @Test
    void validPartTransformsAreAccepted() {
        Map<String, Object> draft = Map.of("rules", List.of(leaf(1, null, "bom.bomNo", "CONCAT",
                Map.of("parts", List.of(
                        "BOM-",
                        Map.of("path", "productCode"),
                        Map.of("path", "productCode", "transform", "STRING"),
                        Map.of("path", "qty", "transform", "INT"),
                        Map.of("path", "createdTime", "transform", "DATE", "datePattern", "yyyyMMdd"),
                        Map.of("path", "unit", "transform", "ENUM", "enumMap", Map.of("PCS", "件")))))));

        MappingDraftValidation result = service.validate(draftOnly(draft));

        assertThat(result.errors()).isEmpty();
        assertThat(result.compiled().jsonata()).contains(
                "\"bomNo\": \"BOM-\" & $s_1.productCode & $s_1.productCode & $number($s_1.qty)"
                        + " & $dateFormat($s_1.createdTime, \"yyyyMMdd\") & $lookup({\"PCS\":\"件\"}, $s_1.unit)");
    }

    /** 行号片段在**数组元素子行**里合法（顶层拼接行上才拦），编译成行号加起始值。 */
    @Test
    void anIndexPartIsAllowedInsideAnArrayChildRow() {
        Map<String, Object> draft = Map.of("rules", List.of(groupWith(
                leaf(null, null, "lineNo", "CONCAT",
                        Map.of("parts", List.of("LOT-", Map.of("transform", "INDEX", "startAt", 1)))))));

        MappingDraftValidation result = service.validate(new MappingDraftRequest(draft,
                spec(arraySpec("bom.bomLines", elementSpec("lineNo", "VARCHAR", false))),
                List.of(), 1, List.of(1), 1, 2));

        assertThat(ruleProblems(result.errors())).isEmpty();
        assertThat(result.compiled().jsonata()).contains("\"lineNo\": \"LOT-\" & ($i0 + 1)");
    }

    /**
     * 只看拼接片段那一条的错：一条 CONCAT 规则只有一个片段（序号 1），目标清单与样例都空着
     * ——同一份草稿里别的检查就没话可说。
     */
    private List<String> partProblems(Object part) {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(1, null, "bom.bomNo", "CONCAT", Map.of("parts", List.of(part)))));

        return problems(service.validate(draftOnly(draft)).errors());
    }

    // ---------- fixture 与共用形状 ----------

    /**
     * 结构有错的草稿：每条问题都定位到出错的那一行（目标字段 + 它所属的数组行）。
     *
     * <p>四类错各一条：叶子缺来源、数组子行缺来源（父行是数组行）、顶层用行号、sourceStep 指到自己；
     * 外加一条编译期才发现的（重复目标）——它说明「结构错」这一档现在也含编译期冲突（#113：以前它只在
     * 启用重建路由时炸），所以跟在同样来自 {@code problems()} 的那几条后面、排在链问题之前。</p>
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

        MappingDraftValidation result = service.validate(request(draft, bomTargets(), List.of(1), 1, 2));

        assertThat(problems(result.errors())).containsExactly(
                "bom.productName|null|映射规则缺少来源字段 source",
                "materialName|bom.bomLines|映射规则缺少来源字段 source",
                "lineNo|null|INDEX 转换只能在数组组内使用: lineNo",
                "bom.bomNo|null|目标字段重复映射: bom.bomNo",
                "bom.productCode|null|sourceStep=2 必须指向执行顺序更早的动作（当前序号 2）");
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

        MappingDraftValidation result = service.validate(
                request(Map.of("rules", List.of(group)), bomLinesTargets(), List.of(1), 1, 2));

        assertThat(problems(result.errors())).containsExactly("qty|bom.bomLines|目标字段重复映射: qty");
    }

    /** sourceStep 的两条错法各报一条：指到自己/更晚，和指向链上没有的序号。 */
    @Test
    void sourceStepMustPointAtAnEarlierActionInTheChain() {
        Map<String, Object> draft = Map.of("rules", List.of(
                leaf(2, "productCode", "bom.productCode"),
                leaf(1, "productName", "bom.productName")));

        MappingDraftValidation result = service.validate(request(draft, thinBomTargets(), List.of(2), 2, 2));

        assertThat(problems(result.errors())).containsExactly(
                "bom.productCode|null|sourceStep=2 必须指向执行顺序更早的动作（当前序号 2）",
                "bom.productName|null|sourceStep=1 在动作链中不存在，请填上游动作序号");
    }

    /** 漏写 sourceStep 是提示不是错误：后端把它当成任务输入，查不出这是个错（#98 D10）。 */
    @Test
    void missingSourceStepIsAHintNotAnError() {
        Map<String, Object> draft = Map.of("rules", List.of(leaf(null, "bomNo", "bom.bomNo")));

        MappingDraftValidation result = service.validate(request(draft, bomNoTarget(), List.of(1), 1, 2));

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

        MappingDraftValidation result = service.validate(request(draft, compiledTargets(), List.of(1), 1, 2));

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
     * 一份校验结果里「必填的目标字段没有配置来源」那几条（`目标字段|父行|说明`）。
     *
     * <p>只挑这一类：同一份草稿上还有日期格式串没试过之类的提示，混进来会让「数组字段整行报一条」
     * 这条断言看不出真正要钉的东西（#113 验收六）。</p>
     */
    private static List<String> missingRequiredWarnings(MappingDraftValidation result) {
        return problems(result.warnings()).stream()
                .filter(problem -> problem.contains("必填的目标字段没有配置来源"))
                .toList();
    }

    /** 问题列表里的错误换回服务内部那个形状，好让只看某一类检查的 fixture 直接对着它断言。 */
    private static List<FieldMappingConfig.RuleProblem> ruleProblems(List<MappingDraftValidation.Problem> errors) {
        return errors.stream()
                .map(problem -> new FieldMappingConfig.RuleProblem(problem.target(), problem.parent(),
                        problem.message()))
                .toList();
    }

    /** 同一个可读形状，给 {@link FieldMappingConfig.RuleProblem} 那一份用（泛型擦除，不能重载）。 */
    private static List<String> sampleProblems(List<FieldMappingConfig.RuleProblem> problems) {
        return problems.stream()
                .map(problem -> problem.target() + "|" + problem.parent() + "|" + problem.message())
                .toList();
    }

    /**
     * 请求：草稿 + 这份草稿要用到的外部事实。
     *
     * <p>默认那份目标清单带上了 {@code bom.vendorName}（必填、{@code bomDraft()} 里没有配）：
     * 「必填的目标字段没有配置来源」正是拿它报出来的——它就是那一条「会触发的草稿」。</p>
     */
    private MappingDraftRequest request(Map<String, Object> draft, List<Integer> earlierIndexes,
                                        Integer defaultSourceStep, Integer actionIndex) {
        return request(draft, fullBomTargets(), erpSourceFields(), earlierIndexes, defaultSourceStep, actionIndex);
    }

    /** 换一份目标清单时的请求（这份草稿要用的就是那一份清单）。 */
    private MappingDraftRequest request(Map<String, Object> draft,
                                        List<MappingDraftRequest.TargetFieldSpec> targets,
                                        List<Integer> earlierIndexes,
                                        Integer defaultSourceStep, Integer actionIndex) {
        return request(draft, targets, erpSourceFields(), earlierIndexes, defaultSourceStep, actionIndex);
    }

    /** 请求的完整形状：草稿 + 目标清单 + 来源样例 + 链上事实。 */
    private MappingDraftRequest request(Map<String, Object> draft,
                                        List<MappingDraftRequest.TargetFieldSpec> targets,
                                        List<SourceField> sourceFields,
                                        List<Integer> earlierIndexes,
                                        Integer defaultSourceStep, Integer actionIndex) {
        return request(draft, targets, sourceFields, 1, earlierIndexes, defaultSourceStep, actionIndex);
    }

    /**
     * 请求的完整形状，外加「这份样例是哪个动作的」。
     *
     * <p>只有一份样例，所以只有来源步骤跟它一样的规则判得了——fixture 里的样例都当动作 1 的探测结果。</p>
     */
    private MappingDraftRequest request(Map<String, Object> draft,
                                        List<MappingDraftRequest.TargetFieldSpec> targets,
                                        List<SourceField> sourceFields,
                                        Integer sourceFieldsStep,
                                        List<Integer> earlierIndexes,
                                        Integer defaultSourceStep, Integer actionIndex) {
        return new MappingDraftRequest(draft, targets, sourceFields, sourceFieldsStep,
                earlierIndexes, defaultSourceStep, actionIndex);
    }

    /** 制品 → BOM 的草稿（mapping-framework.md §4.3 的规则树，来源路径取 ERP 样例里真有的字段）。 */
    private static Map<String, Object> bomDraft() {
        return Map.of("rules", List.of(
                leaf(1, "productCode", "bom.bomNo", "CONCAT",
                        Map.of("parts", List.of("BOM-", Map.of("path", "productCode")))),
                leaf(1, "productCode", "bom.productCode"),
                leaf(1, "productName", "bom.productName"),
                groupWith(
                        leaf(null, "$index", "lineNo", "INDEX", Map.of("startAt", 1)),
                        leaf(null, "materialCode", "materialCode"),
                        leaf(null, "materialName", "materialName"),
                        leaf(null, "quantity", "qty", "INT"),
                        leaf(null, "unit", "unit", "ENUM", Map.of("enumMap", enumMap("PCS", "件", "SET", "套"))))));
    }

    /** 一行枚举映射：来源字段 → 目标字段，表里的键值对按对给。 */
    private static Map<String, Object> enumerated(String source, String target, String... pairs) {
        return leaf(1, source, target, "ENUM", Map.of("enumMap", enumMap(pairs)));
    }

    /** 枚举检查那份样例：`unit` 在样例里出现过 PCS 与 SET 两个值；数组里的 unit 同样两个值。 */
    private static List<SourceField> enumSample() {
        return List.of(
                new SourceField("unit", "STRING", false, List.of("PCS", "SET", "PCS")),
                new SourceField("items[0].unit", "STRING", false, List.of("PCS", "SET")));
    }

    /** 枚举表里已有的键（表里多出来的键不管：样例里没出现的键不报）。 */
    private static Map<String, Object> enumMap(String... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 类型检查那份样例：`unit` 是文本，`quantity` 是数字，数组元素里也各有一个。 */
    private static List<SourceField> numberSample() {
        return List.of(
                source("unit", "STRING"),
                source("quantity", "NUMBER"),
                source("items[0].unit", "STRING"),
                source("items[0].quantity", "NUMBER"));
    }

    /** 一行数组映射：来源数组、目标数组、子规则一次给全。 */
    @SafeVarargs
    private static Map<String, Object> groupWith(Map<String, Object>... children) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("sourceStep", 1);
        group.put("source", "items");
        group.put("target", "bom.bomLines");
        group.put("transform", "STRING");
        group.put("iterate", "FOR_EACH");
        group.put("children", List.of(children));
        return group;
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

    /** 一条样本字段（只看路径与类型时用；样例值另给）。 */
    private static SourceField source(String name, String type) {
        return new SourceField(name, type, false, List.of());
    }

    /** 一条样本字段，连样例值一起给（结构探测给的 `samples` 就是这些原始值）。 */
    private static SourceField source(String name, String type, String... values) {
        return new SourceField(name, type, false, List.of(values));
    }

    /**
     * 目标字段清单——一条一行地写，好在 fixture 里一眼看出这份草稿该有哪些字段。
     *
     * <p>{@link #topSpec} 是顶层字段（都是必填），{@link #elementSpec} 是数组元素里的字段（子行的目标
     * 写的是相对路径，它自己那份路径不带前缀）。</p>
     */
    private static List<MappingDraftRequest.TargetFieldSpec> spec(MappingDraftRequest.TargetFieldSpec... fields) {
        return List.of(fields);
    }

    /** 顶层的必填目标字段。 */
    private static MappingDraftRequest.TargetFieldSpec topSpec(String path, String type) {
        return new MappingDraftRequest.TargetFieldSpec(path, type, true, null);
    }

    /** 顶层的数组目标字段（必填），{@code element} 是数组元素里的字段。 */
    @SafeVarargs
    private static MappingDraftRequest.TargetFieldSpec arraySpec(
            String path, MappingDraftRequest.TargetFieldSpec... element) {
        return new MappingDraftRequest.TargetFieldSpec(path, "ARRAY", true, List.of(element));
    }

    /** 数组元素里的目标字段；{@code children} 是它自己再套一层数组时元素里的字段。 */
    @SafeVarargs
    private static MappingDraftRequest.TargetFieldSpec elementSpec(String path, String type, boolean required,
                                                                  MappingDraftRequest.TargetFieldSpec... children) {
        return new MappingDraftRequest.TargetFieldSpec(path, type, required, List.of(children));
    }

    /**
     * 目标字段清单：制品 → BOM 的目标结构（数组字段带元素内的字段），外加一个必填的 `bom.vendorName`。
     *
     * <p>{@code bom.vendorName} 在 {@code bomDraft()} 里没有配——「必填没配来源」那一条拿这份清单
     * 才报得出来。</p>
     */
    private static List<MappingDraftRequest.TargetFieldSpec> fullBomTargets() {
        List<MappingDraftRequest.TargetFieldSpec> targets = new ArrayList<>(bomTargets());
        targets.add(new MappingDraftRequest.TargetFieldSpec("bom.vendorName", "VARCHAR", true, List.of()));
        return targets;
    }

    /** 目标字段清单：制品 → BOM 的目标结构（数组字段带元素内的字段）。 */
    private static List<MappingDraftRequest.TargetFieldSpec> bomTargets() {
        return spec(
                topSpec("bom.bomNo", "VARCHAR"),
                topSpec("bom.productCode", "VARCHAR"),
                topSpec("bom.productName", "VARCHAR"),
                arraySpec("bom.bomLines",
                        // lineNo / materialCode 标成可选：这份草稿把它们映射在顶层了，组里没有，
                        // 标成必填就会多报一条「必填没配来源」——那一条有自己的 fixture
                        elementSpec("materialName", "VARCHAR", true),
                        elementSpec("lineNo", "INT", false),
                        elementSpec("materialCode", "VARCHAR", false),
                        elementSpec("qty", "INT", false),
                        elementSpec("unit", "VARCHAR", false)));
    }

    /**
     * 窄的那几份目标清单：只声明某一份草稿真映射了的字段，别的都不提。
     *
     * <p>「必填没配来源」是拿**请求里的清单**比草稿的，所以一条只想看别的问题的 fixture 得把清单
     * 收窄到它自己那份草稿——不然会顺带报出一串「草稿里本来就没打算配的字段」，那些噪声会盖住这条
     * fixture 真正要断言的东西。</p>
     */
    private static List<MappingDraftRequest.TargetFieldSpec> thinBomTargets() {
        return spec(topSpec("bom.productCode", "VARCHAR"), topSpec("bom.productName", "VARCHAR"));
    }

    /** 只 qty 标必填：lineNo / materialCode 这份草稿没配，标成必填会让它们各冒一条「必填没配来源」。 */
    private static List<MappingDraftRequest.TargetFieldSpec> bomLinesTargets() {
        return spec(arraySpec("bom.bomLines",
                elementSpec("lineNo", "INT", false),
                elementSpec("materialCode", "VARCHAR", false),
                elementSpec("qty", "INT", true)));
    }

    private static List<MappingDraftRequest.TargetFieldSpec> bomNoTarget() {
        return spec(topSpec("bom.bomNo", "VARCHAR"));
    }

    /** 编译产物那一组的两条规则对应的目标字段。 */
    private static List<MappingDraftRequest.TargetFieldSpec> compiledTargets() {
        return spec(topSpec("bom.bomNo", "VARCHAR"), topSpec("bom.createdDate", "DATE"));
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
