package com.example.edgegateway.mapping.suggest;

/**
 * 一条映射建议：源字段路径 → 目标字段 + 转换，带得分与来源标签。
 * 仅作候选，需实施工程师确认后才生效。
 */
public record MappingSuggestion(
        String sourcePath,
        String targetName,
        double score,
        TransformKind transform,
        String datePattern,
        String constant,
        MatchSource matchSource
) {
}
