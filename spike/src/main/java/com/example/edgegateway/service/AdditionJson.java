package com.example.edgegateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * addition 配置串（JSON）的统一解析工具：解析失败一律抛 IllegalArgumentException，
 * 由各 Service 决定如何处理（数据源校验是 400，连接测试则收敛为 ok=false）。
 */
public final class AdditionJson {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AdditionJson() {
    }

    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("addition 必须是合法 JSON");
        }
    }
}
