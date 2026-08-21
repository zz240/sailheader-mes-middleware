package com.example.edgegateway.mapping;

/** 字段映射转换类型（DS-10.2 起支持组节点）：STRING/INT/DATE/ENUM/CONSTANT 为叶子转换，INDEX/CONCAT 为层级场景新增。 */
public enum TransformType {
    STRING,
    INT,
    DATE,
    ENUM,
    CONSTANT,
    /** 数组组内行号（来源固定为 $index，startAt 为起始值）。 */
    INDEX,
    /** 片段拼接（parts：字符串常量 与 {"path": "..."} 来源片段交替）。 */
    CONCAT
}
