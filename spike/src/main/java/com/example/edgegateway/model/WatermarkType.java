package com.example.edgegateway.model;

/** 增量水位策略：TIMESTAMP 用时间列（配合重叠窗口+幂等），SEQUENCE 用自增列（严格大于）。 */
public enum WatermarkType {
    TIMESTAMP,
    SEQUENCE
}
