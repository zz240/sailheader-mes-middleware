package com.example.edgegateway.config;

import java.util.Map;
import java.util.List;

/**
 * 配置 Map 的取值小工具：统一 null 处理和类型转换。
 *
 * <p>公开（而非包内私有）是因为两个子包都要用它——动作追加配置（{@code config.action}）
 * 与数据源追加配置（{@code config.datasource}）；Java 没有跨子包的包内可见性。
 */
public final class ConfigValues {

    private ConfigValues() {
    }

    public static String str(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static List<Object> list(Object value) {
        return value instanceof List<?> l ? new java.util.ArrayList<>(l) : List.of();
    }

    public static int intValue(Object value, int defaultValue) {
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
