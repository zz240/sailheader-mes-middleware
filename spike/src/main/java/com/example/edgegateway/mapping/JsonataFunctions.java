package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata.JFunction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** JSONata 自定义函数集合（S0 验证：dashjoin registerFunction + JFunction 签名）。 */
public final class JsonataFunctions {

    private JsonataFunctions() {
    }

    /**
     * DATE 格式化函数：签名 (string, string) → string。
     * 兼容 ISO-8601 时间戳（OffsetDateTime/Instant/LocalDateTime）与纯日期串（LocalDate），
     * 无法解析时原样返回。
     */
    public static JFunction dateFormat() {
        return new JFunction((input, args) -> {
            Object value = args.get(0);
            String pattern = (String) args.get(1);
            return formatDate(value, pattern);
        }, "<ss:s>");
    }

    private static String formatDate(Object value, String pattern) {
        if (value == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                pattern == null ? "yyyy-MM-dd" : pattern);
        String raw = String.valueOf(value);
        try {
            return OffsetDateTime.parse(raw).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Instant.parse(raw).atZone(ZoneOffset.UTC).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(raw).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(raw).format(formatter);
        } catch (Exception ignored) {
            return raw;
        }
    }
}
