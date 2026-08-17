package com.example.edgegateway.mapping.suggest;

/** 转换推断的输出类型（比应用层 TransformType 更细，含 BOOLEAN/NUMBER）。 */
public enum TransformKind {
    STRING,
    INT,
    NUMBER,
    DATE,
    ENUM,
    CONSTANT,
    BOOLEAN
}
