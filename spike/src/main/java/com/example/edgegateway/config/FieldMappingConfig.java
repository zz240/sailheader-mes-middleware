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
        validateRule(rules, null, 0);
    }

    /** 递归校验规则树：0 = 顶层，depth 为嵌套组层级（INDEX 只在组内合法）。 */
    private void validateRule(List<FieldMappingRule> list, Integer inheritedStep, int depth) {
        for (FieldMappingRule rule : list) {
            if (rule.target() == null || rule.target().isBlank()) {
                throw new IllegalArgumentException("映射规则缺少目标字段 target");
            }
            boolean group = rule.children() != null && !rule.children().isEmpty();
            if (group) {
                if (rule.iterate() != null && !"FOR_EACH".equals(rule.iterate())) {
                    throw new IllegalArgumentException("组节点 iterate 仅支持 FOR_EACH: " + rule.target());
                }
                if (rule.source() == null || rule.source().isBlank()) {
                    throw new IllegalArgumentException("组节点缺少来源数组 source: " + rule.target());
                }
                Integer step = rule.sourceStep() != null ? rule.sourceStep() : inheritedStep;
                for (FieldMappingRule child : rule.children()) {
                    if (child.sourceStep() != null) {
                        throw new IllegalArgumentException("组内子规则不允许再选 sourceStep（继承组节点）: "
                                + rule.target() + " -> " + child.target());
                    }
                }
                validateRule(rule.children(), step, depth + 1);
                continue;
            }
            if (rule.transform() == TransformType.INDEX) {
                if (depth == 0) {
                    throw new IllegalArgumentException("INDEX 转换只能在数组组内使用: " + rule.target());
                }
                if (!"$index".equals(rule.source())) {
                    throw new IllegalArgumentException("INDEX 转换的 source 必须为 $index: " + rule.target());
                }
                continue;
            }
            if (rule.transform() == TransformType.CONCAT) {
                if (rule.parts() == null || rule.parts().isEmpty()) {
                    throw new IllegalArgumentException("CONCAT 转换需要 parts 片段: " + rule.target());
                }
                for (Object part : rule.parts()) {
                    if (!(part instanceof String s) && !(part instanceof Map<?, ?> m)) {
                        throw new IllegalArgumentException("CONCAT 片段只能是字符串常量或 {\"path\": \"...\"}: " + rule.target());
                    }
                    if (part instanceof Map<?, ?> m && (m.get("path") == null || m.get("path").toString().isBlank())) {
                        throw new IllegalArgumentException("CONCAT 路径片段缺少 path: " + rule.target());
                    }
                }
                continue;
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
        map.put("rules", rules.stream().map(this::ruleMap).toList());
        return map;
    }

    private Map<String, Object> ruleMap(FieldMappingRule r) {
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
        if (r.iterate() != null) {
            rule.put("iterate", r.iterate());
        }
        if (r.children() != null && !r.children().isEmpty()) {
            rule.put("children", r.children().stream().map(this::ruleMap).toList());
        }
        if (r.parts() != null && !r.parts().isEmpty()) {
            rule.put("parts", r.parts());
        }
        if (r.startAt() != null) {
            rule.put("startAt", r.startAt());
        }
        return rule;
    }
}
