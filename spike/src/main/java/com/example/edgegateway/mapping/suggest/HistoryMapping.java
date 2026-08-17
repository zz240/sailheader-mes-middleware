package com.example.edgegateway.mapping.suggest;

import java.util.Map;

/**
 * 一条已确认的映射历史（调用方负责按站点+方向过滤后传入）。
 *
 * @param sourceKey  源结构指纹（表名/队列名/样例 hash），与 SourceStructure.key 对应
 * @param sourcePath 源字段路径
 * @param targetName 目标字段名
 * @param transform  上次确认的转换类型（复用时带出，不重新按样例推断）
 * @param datePattern 日期格式（DATE 时）
 * @param constant   常量值（CONSTANT 时）
 * @param enumMap    枚举映射（ENUM 时）
 * @param hitCount   历史被复用的次数，用于候选排序
 * @param updatedAt  最近确认时间（epoch millis），同键冲突时以最新为准
 */
public record HistoryMapping(
        String sourceKey,
        String sourcePath,
        String targetName,
        TransformKind transform,
        String datePattern,
        String constant,
        Map<String, Object> enumMap,
        int hitCount,
        long updatedAt
) {
}
