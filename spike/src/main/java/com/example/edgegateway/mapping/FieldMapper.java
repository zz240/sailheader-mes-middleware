package com.example.edgegateway.mapping;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按结构化映射规则（MappingRule）把源数据 Map 转换成目标数据 Map。
 *
 * <p>规则只描述"来源字段路径 -> 目标字段名 + 转换方式"，不暴露任何占位符字符串，
 * 对应设计文档里"来源步骤 + 字段路径点选"的交互。</p>
 */
public class FieldMapper {

    private final List<MappingRule> rules;

    public FieldMapper(List<MappingRule> rules) {
        this.rules = rules;
    }

    /**
     * 对一行源数据执行所有映射规则，返回目标 Map。
     */
    public Map<String, Object> apply(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (MappingRule rule : rules) {
            // switch 表达式（Java 14+）：整个 switch 本身会"产出一个值"，
            // 每个 -> 分支都是一个取值分支，等价于旧式"先建变量再挨个赋值"的写法。
            Object value = switch (rule.type()) {
                case CONSTANT -> rule.constant();
                case INT -> {
                    // yield：在带大括号的分支里，用 yield 把这个分支的计算结果
                    // "交出去"给 switch 表达式，作用类似方法里的 return。
                    Object raw = resolve(source, rule.source());
                    yield raw == null ? null : Integer.valueOf(raw.toString());
                }
                case DATE -> {
                    // 单行箭头分支（如上面的 CONSTANT）不需要 yield，箭头后的表达式就是结果；
                    // 多行分支才需要 yield 显式返回。
                    Object raw = resolve(source, rule.source());
                    yield raw == null ? null : formatDate(raw, rule.datePattern());
                }
                case ENUM -> {
                    Object raw = resolve(source, rule.source());
                    yield raw == null ? null : rule.enumMap().get(raw.toString());
                }
                // 默认分支：STRING 及其他类型直接取原值。
                default -> resolve(source, rule.source());
            };
            out.put(rule.target(), value);
        }
        return out;
    }

    /**
     * 按点号分隔的路径（如 data.name）从源 Map 逐级取值。
     */
    private Object resolve(Map<String, Object> source, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * 把时间值按给定格式转成字符串（默认 yyyy-MM-dd）。
     */
    private String formatDate(Object raw, String pattern) {
        if (raw == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                pattern == null ? "yyyy-MM-dd" : pattern);
        if (raw instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(formatter);
        }
        if (raw instanceof LocalDateTime ldt) {
            return ldt.format(formatter);
        }
        return raw.toString();
    }
}
