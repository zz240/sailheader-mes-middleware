package com.example.edgegateway.config;

import com.example.edgegateway.mapping.TransformType;

import java.util.Map;

/** 单条字段映射规则：sourceStep（上下文中的源动作序号）+ 来源字段路径 → 目标字段名 + 转换方式。 */
public record FieldMappingRule(
        Integer sourceStep,
        String source,
        String target,
        TransformType transform,
        String constant,
        Map<String, Object> enumMap,
        String datePattern) {

    public static FieldMappingRule from(Map<String, Object> map) {
        return new FieldMappingRule(
                map.get("sourceStep") == null ? null : Integer.valueOf(map.get("sourceStep").toString()),
                ConfigValues.str(map.get("source")),
                ConfigValues.str(map.get("target")),
                transform(map.get("transform")),
                ConfigValues.str(map.get("constant")),
                ConfigValues.map(map.get("enumMap")),
                ConfigValues.str(map.get("datePattern")));
    }

    private static TransformType transform(Object value) {
        if (value == null || value.toString().isBlank()) {
            return TransformType.STRING;
        }
        return TransformType.valueOf(value.toString());
    }
}
