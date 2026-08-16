package com.example.edgegateway.config;

import java.util.Map;

/** 配置 Map 的取值小工具：统一 null 处理和类型转换。 */
final class ConfigValues {

    private ConfigValues() {
    }

    static String str(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
