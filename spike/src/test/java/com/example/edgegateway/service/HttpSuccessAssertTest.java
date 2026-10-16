package com.example.edgegateway.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP_CALL 成功条件断言的纯逻辑（#148）：**响应体**里某个路径的值决定这一步成不成，
 * 与 HTTP 状态码无关（200 也可能业务失败）。
 *
 * <p>两类配置格：{@code successPath} 要在响应体里取的路径、{@code successExpect} 期望值。
 * 留空即放行——把断言做成"配了才管"是这一票向后兼容的全部本钱（存量链零影响）。</p>
 *
 * <p>比较按**字符串归一**：响应里 {@code "code":0} 与配置里写 {@code "0"} 必须判成功。
 * 严格类型比较会让"看起来一样却判失败"，现场排查成本极高。</p>
 */
class HttpSuccessAssertTest {

    private static final String URL = "http://localhost:9100/mes/api/id-register";

    private static void check(String successPath, String successExpect, String raw) {
        HttpSuccessAssert.check("POST", URL, successPath, successExpect, HttpCallResponse.parse(raw), raw);
    }

    // ---------- 没配 = 与现状完全一致 ----------

    @Test
    void withoutSuccessPathItNeverRejects() {
        assertThatCode(() -> check(null, null, "{\"code\":\"E001\",\"msg\":\"编号重复\"}"))
                .as("两格都空：不看响应体（向后兼容）")
                .doesNotThrowAnyException();
        assertThatCode(() -> check("", "", null))
                .doesNotThrowAnyException();
    }

    @Test
    void expectWithoutPathIsAConfigError() {
        assertThatThrownBy(() -> check(null, "E001", "{\"code\":\"E001\"}"))
                .as("只填期望值不填路径：配置期就该拦，运行期遇到也必须说出来")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successPath");
    }

    // ---------- 取值与比较 ----------

    @Test
    void equalValuePasses() {
        assertThatCode(() -> check("code", "E001", "{\"code\":\"E001\",\"msg\":\"编号重复\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void differentValueFailsWithReadableMessage() {
        assertThatThrownBy(() -> check("code", "0", "{\"code\":\"E001\",\"msg\":\"编号重复\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("目标端业务失败")
                .hasMessageContaining("POST " + URL)
                .hasMessageContaining("code=E001")
                .hasMessageContaining("成功条件 code=0 不满足")
                .hasMessageContaining("编号重复");
    }

    @Test
    void valueComparisonIsNormedToString() {
        assertThatCode(() -> check("code", "0", "{\"code\":0}"))
                .as("响应里的 JSON 数字 0 与配置里的文本 \"0\" 必须判成功")
                .doesNotThrowAnyException();
        assertThatCode(() -> check("code", "true", "{\"code\":true}"))
                .doesNotThrowAnyException();
    }

    @Test
    void nestedPathIsSupported() {
        assertThatCode(() -> check("data.status", "OK", "{\"data\":{\"status\":\"OK\"}}"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> check("data.status", "OK", "{\"data\":{\"status\":\"NG\"}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data.status=NG");
    }

    // ---------- 只判存在 ----------

    @Test
    void expectBlankMeansOnlyExistenceIsChecked() {
        assertThatCode(() -> check("handle", null, "{\"handle\":\"88.1.200020\"}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> check("handle", "", "{\"handle\":0}"))
                .as("只判存在时取值 0 也算有值")
                .doesNotThrowAnyException();
    }

    @Test
    void missingPathFails() {
        assertThatThrownBy(() -> check("handle", null, "{\"code\":\"0\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("handle=null");
    }

    // ---------- 空响应体 ----------

    @Test
    void emptyBodyWithSuccessConditionFails() {
        assertThatThrownBy(() -> check("code", "0", null))
                .as("配了成功条件却收到空响应体：协议违例，显式失败优于静默通过")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("响应体为空");
    }

    // ---------- 可读性 ----------

    @Test
    void longResponseIsTruncatedInTheMessage() {
        String raw = "{\"msg\":\"" + "x".repeat(400) + "\"}";

        assertThatThrownBy(() -> check("code", "0", raw))
                .hasMessageContaining("前 200 字符")
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getMessage().length())
                        .as("只截前 200 字符，不把整份响应塞进错误信息")
                        .isLessThan(400));
    }

    @Test
    void jsonArrayResponseCanBeAssertedByIndex() {
        assertThatCode(() -> check("0.status", "OK",
                "[{\"status\":\"OK\"},{\"status\":\"NG\"}]"))
                .doesNotThrowAnyException();
    }

    @Test
    void nonJsonBodyIsAssertedAgainstItsRawText() {
        assertThatCode(() -> check("", null, "ok")).doesNotThrowAnyException();
        // 非 JSON 文本按原样字符串进上下文：拿它的键去取只会取不到（那就是失败）
        assertThatThrownBy(() -> check("code", "0", "ok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code=null");
    }

    @Test
    void parsedMapIsWhatGetsRead() {
        Object parsed = HttpCallResponse.parse("{\"code\":\"0\"}");
        assertThatCode(() -> HttpSuccessAssert.check("POST", URL, "code", "0", parsed, "{\"code\":\"0\"}"))
                .doesNotThrowAnyException();
        // 调用方传进来的解析结果原样使用（不重复解析原文）
        assertThatCode(() -> HttpSuccessAssert.check("POST", URL, "code", "0",
                Map.of("code", "0"), "ignored"))
                .doesNotThrowAnyException();
        assertThatCode(() -> HttpSuccessAssert.check("POST", URL, "0", "a",
                List.of("a"), "[a]"))
                .doesNotThrowAnyException();
    }
}
