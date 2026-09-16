package com.example.edgegateway.model;

/**
 * 落库那份样例的用途（#119）：编辑器里那两份样例各存各的格子。
 */
public enum MappingSampleKind {

    /** 来源字段样例：{@code refId} = 动作序号（0 = 任务输入，即链上没有更早的动作）。 */
    SOURCE,

    /** 目标字段样例：{@code refId} = 写目标动作 id。 */
    TARGET
}
