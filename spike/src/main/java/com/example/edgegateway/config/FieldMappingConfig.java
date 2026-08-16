package com.example.edgegateway.config;

import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.model.ActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 字段映射动作配置：一组结构化映射规则。 */
public record FieldMappingConfig(List<FieldMappingRule> rules) implements ActionConfig {

    public static FieldMappingConfig from(Map<String, Object> addition) {
        List<FieldMappingRule> rules = new ArrayList<>();
        Object raw = addition.get("rules");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rules.add(FieldMappingRule.from(ConfigValues.map(map)));
                }
            }
        }
        return new FieldMappingConfig(rules);
    }

    @Override
    public ActionType type() {
        return ActionType.FIELD_MAPPING;
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
}
