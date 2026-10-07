package com.example.edgegateway.mapping;

import com.example.edgegateway.config.action.FieldMappingConfig;
import com.example.edgegateway.config.action.FieldMappingRule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DS-10.2 S1：rules → JSONata 编译器 + JsonataFieldMapper 执行器测试。 */
class JsonataRuleCompilerTest {

    private static final FieldMappingRule CONCAT = new FieldMappingRule(1, "product.productCode",
            "bom.bomNo", TransformType.CONCAT, null, Map.of(), null, null, null,
            List.of("BOM-", Map.of("path", "product.productCode")), null);

    /** §4.3 规则树（制品→BOM），用 JsonataFieldMapper 端到端执行。 */
    @Test
    void bomRulesCompileAndExecute() {
        FieldMappingRule group = new FieldMappingRule(1, "product.bomItems", "bom.bomLines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(
                        new FieldMappingRule(null, "$index", "lineNo", TransformType.INDEX,
                                null, Map.of(), null, null, null, null, 1),
                        new FieldMappingRule(null, "materialCode", "materialCode", TransformType.STRING,
                                null, Map.of(), null, null, null, null, null),
                        new FieldMappingRule(null, "materialName", "materialName", TransformType.STRING,
                                null, Map.of(), null, null, null, null, null),
                        new FieldMappingRule(null, "qty", "qty", TransformType.INT,
                                null, Map.of(), null, null, null, null, null),
                        new FieldMappingRule(null, "unit", "unit", TransformType.ENUM,
                                null, Map.of("PCS", "件", "SET", "套"), null, null, null, null, null)),
                null, null);

        List<FieldMappingRule> rules = List.of(
                CONCAT,
                leaf(1, "product.productCode", "bom.productCode", TransformType.STRING),
                leaf(1, "product.productName", "bom.productName", TransformType.STRING),
                group);

        JsonataFieldMapper mapper = new JsonataFieldMapper(rules);
        Object result = mapper.apply(context(1, sourceProduct()));

        Map<String, Object> bom = bom(result);
        assertThat(bom.get("bomNo")).isEqualTo("BOM-PRD-BUMPER-001");
        assertThat(bom.get("productCode")).isEqualTo("PRD-BUMPER-001");
        assertThat(bom.get("productName")).isEqualTo("前保险杠");

        List<Map<String, Object>> lines = bomLines(bom);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("lineNo", 1)
                .containsEntry("materialCode", "MAT-718H")
                .containsEntry("materialName", "718H模具钢")
                .containsEntry("unit", "件");
        assertThat(((Number) lines.get(0).get("qty")).intValue()).isEqualTo(1);
        assertThat(lines.get(1)).containsEntry("lineNo", 2).containsEntry("materialName", "SKD61");
        assertThat(((Number) lines.get(1).get("qty")).intValue()).isEqualTo(24);
    }

    /**
     * #101 验收：制品 → BOM 的层级映射，目标「行号 + 三列元素字段 + 一列外层表头字段」。
     *
     * 规则 JSON 就是前端编辑器保存出来的那一份（`configFromRows` 的形状）：数组那一行带
     * `sourceStep` 与 `iterate=FOR_EACH`，子规则**不带** `sourceStep`（后端校验会拒），
     * 元素字段用相对路径、外层引用用 `$root.` 前缀。断言两件事：文档里的编译产物一致、执行结果一致。
     */
    @Test
    void bomArrayMappingCompilesToMapAndCarriesEveryColumn() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceStep", 1);
        config.put("source", "product.bomItems");
        config.put("target", "bom.bomLines");
        config.put("transform", "STRING");
        config.put("iterate", "FOR_EACH");
        Map<String, Object> lineNo = new LinkedHashMap<>();
        lineNo.put("source", "$index");
        lineNo.put("target", "lineNo");
        lineNo.put("transform", "INDEX");
        lineNo.put("startAt", 1);
        Map<String, Object> itemCode = new LinkedHashMap<>();
        itemCode.put("source", "materialCode");
        itemCode.put("target", "itemCode");
        itemCode.put("transform", "STRING");
        Map<String, Object> itemName = new LinkedHashMap<>();
        // 外层引用：取这份来源数据的顶层对象（#98 B8），组内相对路径取不到它。
        itemName.put("source", "$root.product.productName");
        itemName.put("target", "itemName");
        itemName.put("transform", "STRING");
        Map<String, Object> qty = new LinkedHashMap<>();
        qty.put("source", "qty");
        qty.put("target", "qty");
        qty.put("transform", "INT");
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("source", "unit");
        unit.put("target", "unit");
        unit.put("transform", "ENUM");
        // 键序固定（`Map.of` 的迭代顺序不保证，编译产物的文本断言要看顺序）。
        Map<String, Object> unitMap = new LinkedHashMap<>();
        unitMap.put("PCS", "件");
        unitMap.put("SET", "套");
        unit.put("enumMap", unitMap);
        config.put("children", List.of(lineNo, itemCode, itemName, qty, unit));

        Map<String, Object> addition = Map.of("rules", List.of(config));
        FieldMappingConfig parsed = FieldMappingConfig.from(addition);
        parsed.validate();

        // 编译产物：数组用 $map 生成，外层套 [ ]——$map 的单元素结果会被 JSONata 拍扁成对象（S0 实测）
        String compiled = JsonataRuleCompiler.compile(parsed.rules());
        assertThat(compiled).contains("\"bomLines\": [ $map($s_1.product.bomItems, function($g0, $i0) { {")
                .contains("\"lineNo\": $i0 + 1")
                .contains("\"itemCode\": $g0.materialCode")
                .contains("\"itemName\": $s_1.product.productName")
                .contains("\"qty\": $number($g0.qty)")
                .contains("\"unit\": $lookup({\"PCS\":\"件\",\"SET\":\"套\"}, $g0.unit)");
        // 子规则不带 sourceStep 由 parsed.validate() 上面那一步证明（带了它 FieldMappingConfig 会拒），
        // 前端那边由 checks.ts 断言存出来的规则里子规则没有这个键——编译产物里不会有这个标识符，
        // 在这里 doesNotContain 是一条永远为真的断言。

        // 执行：来源两个元素 → bomLines 两行，每行五个字段都对，行号从 1 起
        JsonataFieldMapper mapper = new JsonataFieldMapper(parsed.rules());
        List<Map<String, Object>> lines = bomLines(bom(mapper.apply(context(1, sourceProduct()))));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("lineNo", 1)
                .containsEntry("itemCode", "MAT-718H")
                .containsEntry("itemName", "前保险杠")
                .containsEntry("unit", "件");
        assertThat(((Number) lines.get(0).get("qty")).intValue()).isEqualTo(1);
        assertThat(lines.get(1)).containsEntry("lineNo", 2)
                .containsEntry("itemCode", "MAT-SKD61")
                .containsEntry("itemName", "前保险杠")
                .containsEntry("unit", "件");
        assertThat(((Number) lines.get(1).get("qty")).intValue()).isEqualTo(24);
    }

    /**
     * 源码里源的数组只有一个元素时，`bomLines` 也必须还是数组（S0 实测的序列扁平化）。
     * 「外层套 [ ]」这条编译约定就是为它存在的。
     */
    @Test
    void singleElementArrayStaysAnArray() {
        FieldMappingRule group = new FieldMappingRule(1, "items", "bom.bomLines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(leaf(null, "name", "name", TransformType.STRING)), null, null);

        JsonataFieldMapper mapper = new JsonataFieldMapper(List.of(group));
        Map<String, Object> source = Map.of("items", List.of(Map.of("name", "only")));

        assertThat(bom(mapper.apply(context(1, source))).get("bomLines"))
                .isInstanceOf(List.class)
                .asList()
                .hasSize(1);
    }

    /** 旧版扁平规则（含数组下标路径）经 JSONata 引擎执行，结果与自研 FieldMapper 一致。 */
    @Test
    void legacyFlatRulesBehaveAsBefore() {
        List<FieldMappingRule> rules = List.of(
                leaf(null, "order_no", "sheet_no", TransformType.STRING),
                leaf(null, "order_date", "sheet_date", TransformType.DATE),
                new FieldMappingRule(null, "status", "status", TransformType.ENUM,
                        null, Map.of("ACTIVE", 1), null, null, null, null, null),
                new FieldMappingRule(null, null, "source", TransformType.CONSTANT,
                        "MES", Map.of(), null, null, null, null, null),
                leaf(null, "qty", "qty", TransformType.INT),
                leaf(null, "data.items[0].name", "firstName", TransformType.STRING));

        JsonataFieldMapper mapper = new JsonataFieldMapper(rules);
        Map<String, Object> source = Map.of(
                "order_no", "PO-001",
                "order_date", "2026-08-21",
                "status", "ACTIVE",
                "qty", "3",
                "data", Map.of("items", List.of(Map.of("name", "张三"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) mapper.apply(context(0, source));

        assertThat(out).containsEntry("sheet_no", "PO-001")
                .containsEntry("sheet_date", "2026-08-21")
                .containsEntry("status", 1)
                .containsEntry("source", "MES")
                .containsEntry("firstName", "张三");
        assertThat(((Number) out.get("qty")).intValue()).isEqualTo(3);
    }

    /** 多来源步骤：步骤 1 头字段 + 步骤 2 行数组，合并进同一目标对象。 */
    @Test
    void multiStepRulesMergeSources() {
        FieldMappingRule group = new FieldMappingRule(2, "lines", "order.lines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(
                        new FieldMappingRule(null, "$index", "lineNo", TransformType.INDEX,
                                null, Map.of(), null, null, null, null, 1),
                        new FieldMappingRule(null, "itemCode", "itemCode", TransformType.STRING,
                                null, Map.of(), null, null, null, null, null)),
                null, null);

        JsonataFieldMapper mapper = new JsonataFieldMapper(List.of(
                leaf(1, "orderNo", "order.orderNo", TransformType.STRING),
                group));

        Map<Integer, Object> context = new LinkedHashMap<>();
        context.put(1, Map.of("orderNo", "PO-9"));
        context.put(2, Map.of("lines", List.of(
                Map.of("itemCode", "A1"), Map.of("itemCode", "A2"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) applyMap(mapper, context).get("order");
        assertThat(order.get("orderNo")).isEqualTo("PO-9");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");
        assertThat(lines.get(0)).containsEntry("lineNo", 1).containsEntry("itemCode", "A1");
        assertThat(lines.get(1)).containsEntry("lineNo", 2).containsEntry("itemCode", "A2");
    }

    /** $root：组内子规则引用头字段（每个数组元素带出父级 bomNo）。 */
    @Test
    void rootReferencePullsHeaderIntoEachLine() {
        FieldMappingRule group = new FieldMappingRule(1, "items", "bom.lines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(
                        new FieldMappingRule(null, "$root.bomNo", "bomNo", TransformType.STRING,
                                null, Map.of(), null, null, null, null, null),
                        new FieldMappingRule(null, "name", "name", TransformType.STRING,
                                null, Map.of(), null, null, null, null, null)),
                null, null);

        JsonataFieldMapper mapper = new JsonataFieldMapper(List.of(group));
        Map<String, Object> source = Map.of("bomNo", "BOM-X",
                "items", List.of(Map.of("name", "a"), Map.of("name", "b")));

        Map<String, Object> bom = bom(mapper.apply(context(1, source)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) bom.get("lines");
        assertThat(lines.get(0)).containsEntry("bomNo", "BOM-X").containsEntry("name", "a");
        assertThat(lines.get(1)).containsEntry("bomNo", "BOM-X").containsEntry("name", "b");
    }

    /** 嵌套组：sections → items 两层数组迭代。 */
    @Test
    void nestedGroupsSupportTwoLevels() {
        FieldMappingRule inner = new FieldMappingRule(null, "items", "lines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(
                        new FieldMappingRule(null, "$index", "lineNo", TransformType.INDEX,
                                null, Map.of(), null, null, null, null, 1),
                        new FieldMappingRule(null, "name", "name", TransformType.STRING,
                                null, Map.of(), null, null, null, null, null)),
                null, null);
        FieldMappingRule outer = new FieldMappingRule(1, "sections", "doc.sections",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(
                        new FieldMappingRule(null, "title", "title", TransformType.STRING,
                                null, Map.of(), null, null, null, null, null),
                        inner),
                null, null);

        JsonataFieldMapper mapper = new JsonataFieldMapper(List.of(outer));
        Map<String, Object> source = Map.of("sections", List.of(
                Map.of("title", "S1", "items", List.of(Map.of("name", "x"))),
                Map.of("title", "S2", "items", List.of(Map.of("name", "y"), Map.of("name", "z")))));

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) applyMap(mapper, context(1, source)).get("doc");
        List<Map<String, Object>> sections = (List<Map<String, Object>>) doc.get("sections");
        assertThat(sections).hasSize(2);
        assertThat(sections.get(0)).containsEntry("title", "S1");
        List<Map<String, Object>> lines0 = (List<Map<String, Object>>) sections.get(0).get("lines");
        assertThat(lines0.get(0)).containsEntry("lineNo", 1).containsEntry("name", "x");
        List<Map<String, Object>> lines1 = (List<Map<String, Object>>) sections.get(1).get("lines");
        assertThat(lines1.get(1)).containsEntry("lineNo", 2).containsEntry("name", "z");
    }

    /** 编译期冲突与配置校验：重复目标 / 前缀冲突 / INDEX 顶层 / 组内 sourceStep / 空 CONCAT。 */
    @Test
    void rejectsInvalidRules() {
        assertThatThrownBy(() -> new JsonataFieldMapper(List.of(
                leaf(1, "a", "x.y", TransformType.STRING),
                leaf(1, "b", "x.y", TransformType.STRING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复映射");

        assertThatThrownBy(() -> new JsonataFieldMapper(List.of(
                leaf(1, "a", "x", TransformType.STRING),
                leaf(1, "b", "x.y", TransformType.STRING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("前缀冲突");

        FieldMappingRule topIndex = new FieldMappingRule(1, "$index", "lineNo", TransformType.INDEX,
                null, Map.of(), null, null, null, null, 1);
        assertThatThrownBy(() -> new FieldMappingConfig(null, null, List.of(topIndex)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INDEX 转换只能在数组组内使用");

        FieldMappingRule badChild = new FieldMappingRule(1, "items", "lines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(new FieldMappingRule(1, "name", "name", TransformType.STRING,
                        null, Map.of(), null, null, null, null, null)),
                null, null);
        assertThatThrownBy(() -> new FieldMappingConfig(null, null, List.of(badChild)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("子规则不允许再选 sourceStep");

        FieldMappingRule emptyConcat = new FieldMappingRule(1, null, "code",
                TransformType.CONCAT, null, Map.of(), null, null, null, List.of(), null);
        assertThatThrownBy(() -> new FieldMappingConfig(null, null, List.of(emptyConcat)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONCAT 转换需要 parts");
    }

    /** FieldMappingConfig 往返：新字段（children/iterate/parts/startAt）不丢。 */
    @Test
    void configRoundTripKeepsHierarchy() {
        FieldMappingRule group = new FieldMappingRule(1, "product.bomItems", "bom.bomLines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(new FieldMappingRule(null, "$index", "lineNo", TransformType.INDEX,
                        null, Map.of(), null, null, null, null, 2)),
                null, null);
        FieldMappingConfig config = new FieldMappingConfig(null, null, List.of(CONCAT, group));

        config.validate();
        FieldMappingConfig restored = FieldMappingConfig.from(config.toMap());
        FieldMappingRule restoredGroup = restored.rules().get(1);
        assertThat(restoredGroup.iterate()).isEqualTo("FOR_EACH");
        assertThat(restoredGroup.children()).hasSize(1);
        assertThat(restoredGroup.children().get(0).transform()).isEqualTo(TransformType.INDEX);
        assertThat(restoredGroup.children().get(0).startAt()).isEqualTo(2);
        FieldMappingRule restoredConcat = restored.rules().get(0);
        assertThat(restoredConcat.transform()).isEqualTo(TransformType.CONCAT);
        assertThat(restoredConcat.parts()).containsExactly("BOM-", Map.of("path", "product.productCode"));
    }

    /** mode=EXPRESSION：手写 JSONata 逃生通道——保存校验、往返、执行。 */
    @Test
    void expressionModeRunsHandWrittenJsonata() {
        String expr = """
                $map($s_1.items, function($v, $i) {
                  {
                    "lineNo": $i + 1,
                    "item": $v.name
                  }
                })
                """;
        FieldMappingConfig config = new FieldMappingConfig("EXPRESSION", expr, List.of());
        config.validate();

        // 往返
        FieldMappingConfig restored = FieldMappingConfig.from(config.toMap());
        assertThat(restored.mode()).isEqualTo("EXPRESSION");
        assertThat(restored.expression()).isEqualTo(expr);

        JsonataFieldMapper mapper = JsonataFieldMapper.fromExpression(expr);
        Object result = mapper.apply(Map.of(1, Map.of(
                "items", List.of(Map.of("name", "a"), Map.of("name", "b")))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) result;
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("lineNo", 1).containsEntry("item", "a");
        assertThat(lines.get(1)).containsEntry("lineNo", 2).containsEntry("item", "b");
    }

    /** mode=EXPRESSION 校验：缺表达式报错；RULES 模式带 expression 报错。 */
    @Test
    void expressionModeValidation() {
        assertThatThrownBy(() -> new FieldMappingConfig("EXPRESSION", null, List.of()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要 expression");
        assertThatThrownBy(() -> new FieldMappingConfig("EXPRESSION", " ", List.of()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要 expression");
        assertThatThrownBy(() -> new FieldMappingConfig(null, "some expr", List.of()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有 mode=EXPRESSION 才允许 expression");
        assertThatThrownBy(() -> JsonataFieldMapper.fromExpression(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** READ_SQL 行里的 JDBC 日期类型（LocalDateTime）在求值前规范化为字符串（本地实测发现）。 */
    @Test
    void jdbcDateValuesAreNormalizedBeforeEvaluation() {
        JsonataFieldMapper mapper = new JsonataFieldMapper(List.of(
                leaf(1, "name", "name", TransformType.STRING),
                leaf(1, "createdAt", "createdAt", TransformType.STRING)));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", "PO-1");
        source.put("createdAt", LocalDateTime.of(2026, 8, 21, 10, 0));

        Map<String, Object> out = applyMap(mapper, context(1, source));
        assertThat(out.get("name")).isEqualTo("PO-1");
        assertThat(out.get("createdAt")).isEqualTo("2026-08-21T10:00");
    }

    /**
     * 第一轮存下来的日期那一行：编译产物**逐字节不变**、跑出来还是那个值（#111 验收六）。
     *
     * <p>#111 只加了「手输格式串」与「格式串合法性校验」，存储格式一个字段都没动——所以一份第一轮
     * 存下来的规则（`transform=DATE` + 显式格式串）编译出来必须还是原来那一串。这条断言是那句
     * 「老配置打开后行为完全一致」在编译这一层的证据（`checks.ts` 那组只管行↔规则那一层）。</p>
     */
    @Test
    void legacyDateRuleCompilesToTheSameExpression() {
        List<FieldMappingRule> rules = List.of(
                new FieldMappingRule(1, "createdTime", "bom.createdDate", TransformType.DATE,
                        null, Map.of(), "yyyyMMdd", null, null, null, null));

        assertThat(JsonataRuleCompiler.compile(rules)).isEqualTo("""
                {
                  "bom": {
                  "createdDate": $dateFormat($s_1.createdTime, "yyyyMMdd")
                  }
                }""");
        assertThat(bom(new JsonataFieldMapper(rules)
                .apply(context(1, Map.of("createdTime", "2026-08-21T10:20:30Z")))))
                .containsEntry("createdDate", "20260821");
    }

    /**
     * 格式串是空串（或只有空白）时按默认格式编译（#111 验收五）。
     *
     * <p>编辑器保证清空的手输框不会把空串写进规则，但手写/存量配置里可能是这个形状：原样送进
     * `$dateFormat` 的话目标收到的是一串空。与「键不在」同一条口径——都算没写。</p>
     */
    @Test
    void blankDatePatternFallsBackToTheDefaultFormat() {
        for (String pattern : new String[]{"", "   "}) {
            List<FieldMappingRule> rules = List.of(
                    new FieldMappingRule(1, "createdTime", "bom.createdDate", TransformType.DATE,
                            null, Map.of(), pattern, null, null, null, null));

            assertThat(JsonataRuleCompiler.compile(rules))
                    .as("[" + pattern + "]")
                    .contains("$dateFormat($s_1.createdTime, \"yyyy-MM-dd\")");
            assertThat(bom(new JsonataFieldMapper(rules)
                    .apply(context(1, Map.of("createdTime", "2026-08-21T10:20:30Z")))))
                    .as("[" + pattern + "]")
                    .containsEntry("createdDate", "2026-08-21");
        }
    }

    // ---------- 片段转换（#112）：拼接的每一段各自挂一个转换 ----------

    /**
     * 老配置（字符串片段 / `{"path"}` 片段）打开 → 保存 → 编译产物**逐字节不变**（#112 验收八）。
     *
     * <p>这条是本票最重要的一条：存储形状第一次变化，只有「编译产物一个字节都没动」能证明
     * 「没迁移、也没破坏」。断言的是**写死的产物文本**（不是拿同一份实现再算一遍），加上真跑出来的值
     * ——前者管形状，后者管行为。</p>
     */
    @Test
    void legacyConcatPartsCompileByteForByteTheSame() {
        assertThat(JsonataRuleCompiler.compile(List.of(CONCAT))).isEqualTo("""
                {
                  "bom": {
                  "bomNo": "BOM-" & $s_1.product.productCode
                  }
                }""");

        Object result = new JsonataFieldMapper(List.of(CONCAT)).apply(context(1, sourceProduct()));
        assertThat(bom(result)).containsEntry("bomNo", "BOM-PRD-BUMPER-001");
    }

    /**
     * 存储形状第一次变化：片段可以是字符串常量、`{"path"}`、`{"path", "transform", 参数}`、`{"transform": "INDEX"}`。
     *
     * <p>老形状是加工片段的退化情形（没有 `transform` 就是直接取值），读取兼容、不做数据迁移。编译产物仍然
     * 是一段人眼能读的 `&` 拼接串——这条断言就是「产物还读得懂」的证据。</p>
     */
    @Test
    void concatPartsCarryTheirOwnTransform() {
        FieldMappingRule rule = new FieldMappingRule(1, null, "bom.bomNo", TransformType.CONCAT,
                null, Map.of(), null, null, null,
                List.of("BOM-",
                        Map.of("path", "product.productCode", "transform", "STRING"),
                        Map.of("path", "createdTime", "transform", "DATE", "datePattern", "yyyyMMdd"),
                        Map.of("path", "status", "transform", "ENUM", "enumMap", Map.of("A", "启用")),
                        Map.of("path", "qty", "transform", "INT")),
                null);

        assertThat(JsonataRuleCompiler.compile(List.of(rule))).isEqualTo("""
                {
                  "bom": {
                  "bomNo": "BOM-" & $s_1.product.productCode & $dateFormat($s_1.createdTime, "yyyyMMdd") & $lookup({"A":"启用"}, $s_1.status) & $number($s_1.qty)
                  }
                }""");

        Object result = new JsonataFieldMapper(List.of(rule)).apply(context(1, Map.of(
                "product", Map.of("productCode", "PRD-001"),
                "createdTime", "2026-08-21T10:20:30Z",
                "status", "A",
                "qty", 24)));
        assertThat(bom(result)).containsEntry("bomNo", "BOM-PRD-00120260821启用24");
    }

    /** 日期片段没写格式串时按默认值（与行级 DATE 同一条口径）。 */
    @Test
    void aDatePartWithoutAPatternUsesTheDefaultFormat() {
        FieldMappingRule rule = new FieldMappingRule(1, null, "bom.bomNo", TransformType.CONCAT,
                null, Map.of(), null, null, null,
                List.of(Map.of("path", "createdTime", "transform", "DATE")), null);

        assertThat(JsonataRuleCompiler.compile(List.of(rule)))
                .contains("$dateFormat($s_1.createdTime, \"yyyy-MM-dd\")");
    }

    /** 行号片段：源就是行号本身（不带 `path`），编译成数组元素的行号加起始值。 */
    @Test
    void indexPartCompilesToTheArrayRowNumber() {
        FieldMappingRule group = new FieldMappingRule(1, "product.bomItems", "bom.bomLines",
                TransformType.STRING, null, Map.of(), null, "FOR_EACH",
                List.of(new FieldMappingRule(null, null, "lineNo", TransformType.CONCAT,
                        null, Map.of(), null, null, null,
                        List.of("LOT-", Map.of("transform", "INDEX", "startAt", 1)), null)),
                null, null);

        // 括号是必须的：引擎里 `&` 与 `+` 同级左结合，`"LOT-" & $i0 + 1` 会当成 `("LOT-" & $i0) + 1` 抛错
        assertThat(JsonataRuleCompiler.compile(List.of(group)))
                .contains("\"lineNo\": \"LOT-\" & ($i0 + 1)");

        Object result = new JsonataFieldMapper(List.of(group)).apply(context(1, Map.of(
                "product", Map.of("bomItems", List.of(
                        Map.of("materialCode", "MAT-1"), Map.of("materialCode", "MAT-2"))))));
        assertThat(bomLines(bom(result))).extracting(line -> line.get("lineNo"))
                .containsExactly("LOT-1", "LOT-2");
    }

    /**
     * 顶层拼接行上的行号片段：编译器也不放行（与结构校验同一条消息，服务那边合成一条）。
     *
     * <p>不拦的话它会编成 `($i-1 + 1)`——`$i` 在顶层根本没绑定，「编译产物」那一页显示的就是一段
     * 跑不起来的东西。结构校验本来就拦（保存那道 400 也会响），这里补的是**编译这一侧**：产物面板不该
     * 给人看一段假的表达式。</p>
     */
    @Test
    void anIndexPartOnATopLevelConcatIsRejectedWhenCompiling() {
        FieldMappingRule rule = new FieldMappingRule(1, null, "bom.bomNo", TransformType.CONCAT,
                null, Map.of(), null, null, null,
                List.of("LOT-", Map.of("transform", "INDEX", "startAt", 1)), null);

        assertThatThrownBy(() -> JsonataRuleCompiler.compile(List.of(rule)))
                .isInstanceOf(JsonataRuleCompiler.CompileException.class)
                .hasMessage("拼接第 2 个片段：行号片段只能在数组映射的子行里用: bom.bomNo");
    }

    /**
     * 认不出的片段转换：报清楚是**哪个片段**（序号从 1 数），别让人自己去找。
     *
     * <p>消息形状与结构校验那条一致（`MappingSampleChecks`/`FieldMappingConfig` 也用「拼接第 N 个片段」）。</p>
     */
    @Test
    void anUnknownPartTransformIsReportedWithItsIndex() {
        FieldMappingRule rule = new FieldMappingRule(1, null, "bom.bomNo", TransformType.CONCAT,
                null, Map.of(), null, null, null,
                List.of("BOM-", Map.of("path", "productCode", "transform", "NOT_A_TRANSFORM")), null);

        assertThatThrownBy(() -> JsonataRuleCompiler.compile(List.of(rule)))
                .isInstanceOf(JsonataRuleCompiler.CompileException.class)
                .hasMessageContaining("拼接第 2 个片段")
                .hasMessageContaining("NOT_A_TRANSFORM");
    }

    // ---------- helpers ----------
    private static FieldMappingRule leaf(Integer step, String source, String target, TransformType type) {
        return new FieldMappingRule(step, source, target, type, null, Map.of(), null,
                null, null, null, null);
    }

    /**
     * 保存那道门与编译器报的是**同一句话**（#113、#114）。
     *
     * <p>目标树那两种走法（`JsonataRuleCompiler.insert` 与 `FieldMappingConfig.insertTarget`）是两份实现
     * ——写在一起的话编译器要反过来依赖 config 包。这份用例就是它们之间的约束：同一批问题，两边报出来的
     * 话必须逐字相同，动了其中一边而没动另一边，这里会红。</p>
     *
     * <p>#114 补上最后一条漏的：目标字段路径空了一段（`a..b` / `.a` / 整条都是点的 `.`）。编译器一直拒它，
     * 门以前只 return、不报——停用状态下存得进去，启用重建路由时才炸。</p>
     */
    @Test
    void savingGateAndCompilerReportTheSameConflicts() {
        // 重复目标字段
        assertThat(configProblems(List.of(
                leaf(1, "a", "sheet_no", TransformType.STRING),
                leaf(1, "b", "sheet_no", TransformType.STRING))))
                .containsExactly(compileMessage(List.of(
                        leaf(1, "a", "sheet_no", TransformType.STRING),
                        leaf(1, "b", "sheet_no", TransformType.STRING))));

        // 前缀冲突：先映射 sheet_no，再映射它的子路径
        List<FieldMappingRule> parentThenChild = List.of(
                leaf(1, "a", "sheet_no", TransformType.STRING),
                leaf(1, "b", "sheet_no.qty", TransformType.STRING));
        assertThat(configProblems(parentThenChild)).containsExactly(compileMessage(parentThenChild));

        // 前缀冲突：先映射子路径，再映射它本身
        List<FieldMappingRule> childThenParent = List.of(
                leaf(1, "b", "sheet_no.qty", TransformType.STRING),
                leaf(1, "a", "sheet_no", TransformType.STRING));
        assertThat(configProblems(childThenParent)).containsExactly(compileMessage(childThenParent));

        // 组内子规则重复：两条走法都要一路走进去
        FieldMappingRule group = new FieldMappingRule(1, "items", "lines", TransformType.STRING,
                null, Map.of(), null, "FOR_EACH", List.of(
                        leaf(null, "a", "qty", TransformType.STRING),
                        leaf(null, "b", "qty", TransformType.STRING)), null, null);
        List<FieldMappingRule> inGroup = List.of(group);
        assertThat(configProblems(inGroup)).containsExactly(compileMessage(inGroup));

        // 目标字段路径里空的一段（`a..b` / `.a` / 整条都是点的 `.`）：编译器一直拒它，门以前放它过去（#114）
        for (String illegalTarget : List.of("sheet_no..qty", ".sheet_no", ".")) {
            List<FieldMappingRule> badPath = List.of(leaf(1, "a", illegalTarget, TransformType.STRING));
            assertThat(configProblems(badPath))
                    .as("目标字段路径不合法：%s", illegalTarget)
                    .containsExactly(compileMessage(badPath));
        }

        // 目标字段整条空着（键都不写 / 空串）：两边都拒，而且说**同一句话**——编译器以前在这里 NPE，
        // 「校验配置」那条链路于是把一条草稿问题变成 500（#114）
        for (FieldMappingRule blankTarget : List.of(
                leaf(1, "a", null, TransformType.STRING), leaf(1, "a", "", TransformType.STRING))) {
            List<FieldMappingRule> blanks = List.of(blankTarget);
            assertThat(configProblems(blanks)).containsExactly(compileMessage(blanks));
        }

        // `a.` 不是错：split 会把结尾的空段丢掉，两边都当 `a`——钉住它，免得有人顺手把编译器收紧
        List<FieldMappingRule> trailingDot = List.of(leaf(1, "a", "a.", TransformType.STRING));
        assertThat(configProblems(trailingDot)).isEmpty();
        assertThat(JsonataRuleCompiler.compile(trailingDot)).contains("\"a\"");
    }

    /** 保存那道门（`FieldMappingConfig.problems()`）对着同一批冲突报出来的话。 */
    private static List<String> configProblems(List<FieldMappingRule> rules) {
        return new FieldMappingConfig(null, null, rules).problems().stream()
                .map(FieldMappingConfig.RuleProblem::message)
                .toList();
    }

    /** 编译器对着同一批冲突抛出来的那一句。 */
    private static String compileMessage(List<FieldMappingRule> rules) {
        try {
            JsonataRuleCompiler.compile(rules);
        } catch (JsonataRuleCompiler.CompileException e) {
            return e.getMessage();
        }
        throw new AssertionError("这份规则本该编译不过：" + rules);
    }


    private static Map<Integer, Object> context(int step, Object value) {
        Map<Integer, Object> context = new LinkedHashMap<>();
        context.put(step, value);
        return context;
    }

    private static Map<String, Object> sourceProduct() {
        return Map.of(
                "product", Map.of(
                        "productCode", "PRD-BUMPER-001",
                        "productName", "前保险杠",
                        "bomItems", List.of(
                                Map.of("materialCode", "MAT-718H", "materialName", "718H模具钢",
                                        "qty", 1, "unit", "PCS"),
                                Map.of("materialCode", "MAT-SKD61", "materialName", "SKD61",
                                        "qty", 24, "unit", "PCS"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applyMap(JsonataFieldMapper mapper, Map<Integer, Object> context) {
        return (Map<String, Object>) mapper.apply(context);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bom(Object result) {
        return (Map<String, Object>) ((Map<String, Object>) result).get("bom");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bomLines(Map<String, Object> bom) {
        return (List<Map<String, Object>>) bom.get("bomLines");
    }
}
