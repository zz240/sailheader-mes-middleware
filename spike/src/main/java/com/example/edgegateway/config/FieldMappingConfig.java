package com.example.edgegateway.config;

import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.model.ActionKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 字段映射动作配置（FIELD_MAP）：一组结构化映射规则。 */
public record FieldMappingConfig(List<FieldMappingRule> rules) implements ActionConfig {

    public static FieldMappingConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        map.keySet().stream()
                .filter(k -> !Set.of("rules").contains(k))
                .findFirst()
                .ifPresent(k -> {
                    throw new IllegalArgumentException("未知字段: " + k);
                });
        List<FieldMappingRule> rules = new ArrayList<>();
        Object raw = map.get("rules");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> ruleMap) {
                    rules.add(FieldMappingRule.from(ConfigValues.map(ruleMap)));
                }
            }
        }
        return new FieldMappingConfig(rules);
    }

    @Override
    public ActionKind type() {
        return ActionKind.FIELD_MAP;
    }

    @Override
    public void validate() {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("字段映射动作需要至少一条映射规则");
        }
        for (FieldMappingRule rule : rules) {
            if (rule.target() == null || rule.target().isBlank()) {
                throw new IllegalArgumentException("映射规则缺少目标字段 target");
            }
            if (rule.transform() == TransformType.CONSTANT
                    && (rule.constant() == null || rule.constant().isBlank())) {
                throw new IllegalArgumentException("常量转换需要 constant");
            }
            if (rule.transform() == TransformType.ENUM && rule.enumMap().isEmpty()) {
                throw new IllegalArgumentException("枚举转换需要 enumMap");
            }
            if (rule.transform() != TransformType.CONSTANT
                    && (rule.source() == null || rule.source().isBlank())) {
                throw new IllegalArgumentException("映射规则缺少来源字段 source");
            }
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rules", rules.stream().map(r -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            if (r.sourceStep() != null) {
                rule.put("sourceStep", r.sourceStep());
            }
            if (r.source() != null) {
                rule.put("source", r.source());
            }
            rule.put("target", r.target());
            rule.put("transform", r.transform().name());
            if (r.constant() != null) {
                rule.put("constant", r.constant());
            }
            if (r.enumMap() != null && !r.enumMap().isEmpty()) {
                rule.put("enumMap", r.enumMap());
            }
            if (r.datePattern() != null) {
                rule.put("datePattern", r.datePattern());
            }
            return rule;
        }).toList());
        return map;
    }
}
