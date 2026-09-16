package com.example.edgegateway.config;

import com.example.edgegateway.mapping.TransformType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单条字段映射规则（DS-10.2 升级为递归树）。
 *
 * <p>叶子规则：sourceStep + source（来源字段路径）→ target + transform（转换方式）。</p>
 * <p>组节点规则（children 非空）：source 为数组路径，iterate=FOR_EACH，children 为相对当前
 * 数组元素映射的子规则（sourceStep 必须为空，继承组节点的 sourceStep）。</p>
 * <p>特殊来源：$index（INDEX 行号，配 startAt）、$root（组内引用当前 sourceStep 的根对象，
 * 即跨层级引用）。</p>
 */
public record FieldMappingRule(
        Integer sourceStep,
        String source,
        String target,
        TransformType transform,
        String constant,
        Map<String, Object> enumMap,
        String datePattern,
        /** 组节点迭代方式（当前仅 FOR_EACH）。 */
        String iterate,
        /** 组节点的子规则（相对路径，递归）。 */
        List<FieldMappingRule> children,
        /** CONCAT 片段：字符串常量 与 {"path": "..."} 来源片段。 */
        List<Object> parts,
        /** INDEX 行号起始值（默认 1）。 */
        Integer startAt) {

    public static FieldMappingRule from(Map<String, Object> map) {
        List<FieldMappingRule> children = new ArrayList<>();
        Object rawChildren = map.get("children");
        if (rawChildren instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> childMap) {
                    children.add(FieldMappingRule.from(ConfigValues.map(childMap)));
                }
            }
        }
        return new FieldMappingRule(
                map.get("sourceStep") == null ? null : Integer.valueOf(map.get("sourceStep").toString()),
                ConfigValues.str(map.get("source")),
                ConfigValues.str(map.get("target")),
                transform(map.get("transform")),
                ConfigValues.str(map.get("constant")),
                ConfigValues.map(map.get("enumMap")),
                ConfigValues.str(map.get("datePattern")),
                ConfigValues.str(map.get("iterate")),
                children,
                ConfigValues.list(map.get("parts")),
                map.get("startAt") == null ? null : Integer.valueOf(map.get("startAt").toString()));
    }

    private static TransformType transform(Object value) {
        if (value == null || value.toString().isBlank()) {
            return TransformType.STRING;
        }
        return TransformType.valueOf(value.toString());
    }

    /**
     * 把这一条组规则换成「只有这些子行」的那一份：来源数组、行号起始值、转换参数都不动。
     *
     * <p>试算逐行归因时用得上（#116）：数组子行写的是相对路径，单独编出来 {@code $g0} 根本没绑定，
     * 得把它**放回外层组里**跑——也就是把外层组换成只装这一个子行的那一份。写在这么个地方而不是
     * 在试算那一侧手抄十一个分量：记录加字段时不会漏，也不会把顺序抄错（错了编译器不会拦）。</p>
     */
    public FieldMappingRule withChildren(List<FieldMappingRule> children) {
        return new FieldMappingRule(sourceStep, source, target, transform, constant, enumMap, datePattern,
                iterate, List.copyOf(children), parts, startAt);
    }
}
