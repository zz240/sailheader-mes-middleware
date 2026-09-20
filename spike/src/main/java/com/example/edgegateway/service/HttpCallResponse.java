package com.example.edgegateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP 响应体的解析（#123）：目标端返回的原始文本 → 链上上下文里的那个值。
 *
 * <p>契约（{@code docs/action-chain-design.md} §2）：每个动作完成后写 {@code context[actionIndex] = 输出}；
 * HTTP 动作的输出就是响应。解析只按 JSON 的顶层形状分流，**不猜字段名、不截断**——
 * 响应里有什么就是什么，怎么取是下游规则的事（{@code sourceStep + sourcePath}）。</p>
 */
final class HttpCallResponse {

    /** 解析 JSON 用（ObjectMapper 线程安全，复用一份；与 {@code JsonataFunctions} 同一手法）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpCallResponse() {
    }

    /**
     * JSON 对象 → {@code Map}、JSON 数组 → {@code List}、JSON 标量 → 对应 Java 值，
     * 非 JSON 文本 → 原样字符串（如 MES 回的 {@code ok}），空体（204 / 空 200）→ {@code null}。
     */
    static Object parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Object.class);
        } catch (Exception notJson) {
            return raw;
        }
    }
}
