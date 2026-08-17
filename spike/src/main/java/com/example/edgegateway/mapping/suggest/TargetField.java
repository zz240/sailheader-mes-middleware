package com.example.edgegateway.mapping.suggest;

/** 目标字段清单中的单个字段；required 决定是否计入自动填充与必配校验。 */
public record TargetField(String name, boolean required) {
}
