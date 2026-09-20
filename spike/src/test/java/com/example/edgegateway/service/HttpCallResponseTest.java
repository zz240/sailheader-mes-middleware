package com.example.edgegateway.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #123 口径 1：响应原文 → {@code context[本动作序号]} 那个值的分流。 */
class HttpCallResponseTest {

    @Test
    void parsesJsonObjectIntoMap() {
        Object parsed = HttpCallResponse.parse("{\"code\":\"0\",\"results\":[{\"handle\":\"88.1.1\"}]}");

        assertThat(parsed).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) parsed).get("code")).isEqualTo("0");
    }

    @Test
    void parsesJsonArrayIntoList() {
        Object parsed = HttpCallResponse.parse("[{\"FCode\":\"C-1\"}]");

        assertThat(parsed).isInstanceOf(List.class);
        assertThat((List<?>) parsed).hasSize(1);
    }

    @Test
    void parsesJsonScalarIntoItsJavaValue() {
        assertThat(HttpCallResponse.parse("123")).isEqualTo(123);
        assertThat(HttpCallResponse.parse("\"abc\"")).isEqualTo("abc");
        assertThat(HttpCallResponse.parse("true")).isEqualTo(true);
    }

    @Test
    void keepsNonJsonTextAsIs() {
        // 目标端回一句 "ok" 也是合法响应：原样进 context，不因为不是 JSON 就把这一步判成失败
        assertThat(HttpCallResponse.parse("ok")).isEqualTo("ok");
        assertThat(HttpCallResponse.parse("  code=0; handled  ")).isEqualTo("  code=0; handled  ");
    }

    @Test
    void parsesEmptyBodyAsNull() {
        assertThat(HttpCallResponse.parse(null)).isNull();
        assertThat(HttpCallResponse.parse("")).isNull();
        assertThat(HttpCallResponse.parse("   ")).isNull();
    }
}
