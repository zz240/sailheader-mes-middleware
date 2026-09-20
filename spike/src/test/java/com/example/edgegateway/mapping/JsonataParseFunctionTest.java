package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code $parse} 自定义函数的规格（「一单多行 → 一条报文」那个形状的开关）。
 *
 * <p>为什么要有它：源库侧用 JSON 聚合（SQL Server {@code FOR JSON PATH} / MySQL {@code JSON_ARRAYAGG}）
 * 把明细折成一列**字符串**，映射这边得先把它变回数组才能 {@code $map}。引擎自带的函数里没有 {@code $parse}
 * （实测报 "Attempted to invoke a non-function"）。</p>
 *
 * <p>测的是函数在表达式里的可见行为（能不能被 $map 消费、坏输入会不会炸），不碰实现细节。</p>
 */
class JsonataParseFunctionTest {

    private Jsonata withParse(String expression) {
        Jsonata jsonata = Jsonata.jsonata(expression);
        jsonata.registerFunction("parse", JsonataFunctions.parseJson());
        return jsonata;
    }

    @Test
    void turnsAJsonArrayStringIntoAnArrayUsableByMap() {
        String expression = """
                {
                  "no": FBillNo,
                  "lines": $map($parse(lines_json), function($l, $i) { { "seq": $l.FSeq, "item": $l.FItemCode } })
                }
                """;
        Map<String, Object> row = Map.of(
                "FBillNo", "QC-1",
                "lines_json", "[{\"FSeq\":1,\"FItemCode\":\"DIM-001\"},{\"FSeq\":2,\"FItemCode\":\"DIM-002\"}]");

        Object result = withParse(expression).evaluate(row);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result;
        assertThat(out.get("no")).isEqualTo("QC-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) out.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("seq", 1).containsEntry("item", "DIM-001");
        assertThat(lines.get(1)).containsEntry("seq", 2).containsEntry("item", "DIM-002");
    }

    @Test
    void turnsAJsonObjectStringIntoAnObject() {
        String expression = "{ \"inner\": $parse(payload).code }";
        Object result = withParse(expression).evaluate(Map.of("payload", "{\"code\":\"OK\",\"n\":3}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result;
        assertThat(out.get("inner")).isEqualTo("OK");
    }

    /** 已经是结构（连接器直接给了 Map/List）时原样返回——存量数据里两种形状都可能有。 */
    @Test
    void alreadyStructuredInputPassesThrough() {
        String expression = "{ \"n\": $count($parse(lines)) }";
        Object result = withParse(expression).evaluate(Map.of("lines", List.of(1, 2, 3)));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result;
        assertThat(out.get("n")).isEqualTo(3);
    }

    @Test
    void nullInputGivesNull() {
        String expression = "{ \"v\": $parse(missing) }";
        Object result = withParse(expression).evaluate(Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result;
        assertThat(out.get("v")).isNull();
    }

    /** 坏输入必须炸（映射步 FAILED），不能静默出空——与其它求值错误一个口径。 */
    @Test
    void invalidJsonFailsLoudly() {
        String expression = "{ \"v\": $parse(payload) }";

        assertThatThrownBy(() -> withParse(expression).evaluate(Map.of("payload", "{not json")))
                .hasMessageContaining("$parse");
    }
}
