package com.example.edgegateway.mapping.suggest;

/** 目标字段清单中的单个字段；required 决定是否计入自动填充与必配校验，array 标记数组目标（组建议）。 */
public record TargetField(String name, boolean required, boolean array) {

    /** 兼容旧调用点：默认非数组。 */
    public TargetField(String name, boolean required) {
        this(name, required, false);
    }
}
