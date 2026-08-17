package com.example.edgegateway.mapping.suggest;

import java.util.Map;

/**
 * 一条映射建议：源字段路径 → 目标字段 + 转换，带得分与来源标签。
 * 仅作候选，需实施工程师确认后才生效。
 *
 * @param datePattern 日期格式（DATE 时；历史复用时为上次确认的格式）
 * @param constant    常量值（CONSTANT 时）
 * @param enumMap     枚举映射（ENUM 时；历史复用时为上次确认的映射）
 */
public record MappingSuggestion(
        String sourcePath,
        String targetName,
        double score,
        TransformKind transform,
        String datePattern,
        String constant,
        Map<String, Object> enumMap,
        MatchSource matchSource
) {
}
