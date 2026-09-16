package com.example.edgegateway.mapping;

import com.example.edgegateway.config.FieldMappingRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 表达式求值的资源上限（#116 验收七、九）：超时与递归深度真的挂上去了，写坏的表达式跑不到把网关卡住。
 *
 * <p>断言的是外部行为——「这条表达式跑起来会不会被拦住、拦住时说什么」，不看内部挂在哪一层。
 * 覆盖两条路：运行时手写表达式（{@code mode=EXPRESSION}，与路由那边同一个入口
 * {@link JsonataFieldMapper#fromExpression(String)}）与结构化规则那条路（同一份上限参数）。</p>
 */
class JsonataFieldMapperLimitsTest {

    /** 尾递归死循环：引擎按递归深度拦住它（不是等它自己停下来）。 */
    private static final String INFINITE_RECURSION = "( $f := function($n){ $f($n + 1) }; $f(1) )";

    /**
     * 运行时的 {@code mode=EXPRESSION} 求值带上同一套上限（#116 验收九）：不传上限的那个入口
     * （路由生成用的就是它）也必须拦住死循环——默认 5 秒到点就停（这个引擎里的死循环是尾递归，
     * 被优化成循环、深度不涨，所以拦它的是超时那一条）。
     */
    @Test
    void runtimeExpressionModeStopsAnInfiniteRecursion() {
        JsonataFieldMapper mapper = JsonataFieldMapper.fromExpression(INFINITE_RECURSION);

        assertThatThrownBy(() -> mapper.apply(Map.of(0, Map.of("a", 1))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timeout");
    }

    /**
     * 结构化规则那条路同样带上限：把超时调到一个不可能满足的值（负数），求值当场停下并说是超时。
     *
     * <p>用不可能满足的值是为了**确定性**——断言的是「这道边界真的接上了」，不是「默认 5 秒准不准」；
     * 默认值那一条由上面那个死循环用例（默认上限下被拦住）与下面那个「正常映射照样跑完」共同保住。</p>
     */
    @Test
    void rulesModeAlsoCarriesTheTimeoutLimit() {
        JsonataFieldMapper mapper = new JsonataFieldMapper(
                List.of(rule(1, "bomNo", "bom.bomNo")), new JsonataLimits(-1L, 100));

        assertThatThrownBy(() -> mapper.apply(Map.of(1, Map.of("bomNo", "BOM-1"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timeout");
    }

    /**
     * 默认上限不是「一跑就拦」：一份正常的映射（200 个数组元素、子行里一个数字转换）照样跑完。
     *
     * <p>这一条不是凑数：引擎那个深度计数器会把每个元素里的内置函数调用也算成一层，卡紧了这份映射
     * 就会在「整份跑」那一步被自己拦下（实测深度与数组长度同阶）。默认值取得宽正是为了它。</p>
     */
    @Test
    void aNormalMappingFinishesUnderTheDefaultLimits() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            items.add(Map.of("materialCode", "M-" + i, "quantity", "2"));
        }
        JsonataFieldMapper mapper = new JsonataFieldMapper(List.of(
                rule(1, "bomNo", "bom.bomNo"),
                new FieldMappingRule(1, "items", "bom.bomLines", TransformType.STRING, null, Map.of(),
                        null, "FOR_EACH", List.of(
                        rule(null, "materialCode", "materialCode"),
                        new FieldMappingRule(null, "quantity", "qty", TransformType.INT, null, Map.of(),
                                null, null, List.of(), List.of(), null)),
                        List.of(), null)));

        Map<Integer, Object> context = new LinkedHashMap<>();
        context.put(1, Map.of("bomNo", "BOM-1", "items", items));

        // 不抛就是过了；再核一眼它真算出了东西（没被拦成一份空结果）
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) mapper.apply(context);
        @SuppressWarnings("unchecked")
        Map<String, Object> bom = (Map<String, Object>) output.get("bom");
        assertThat(bom.get("bomNo")).isEqualTo("BOM-1");
        assertThat((List<?>) bom.get("bomLines")).hasSize(200);
    }

    /** 一条规则：{@code sourceStep} 为 null 就是组内子行（继承组节点）。 */
    private static FieldMappingRule rule(Integer sourceStep, String source, String target) {
        return new FieldMappingRule(sourceStep, source, target, TransformType.STRING, null, Map.of(),
                null, null, List.of(), List.of(), null);
    }
}
