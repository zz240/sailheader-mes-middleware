package com.example.edgegateway.model;

/** 动作种类（DS-8 语义化）：读写意图显式化，不再靠位置与 addition 内容猜测。 */
public enum ActionKind {
    MQ_CONSUME,
    READ_SQL,
    FIELD_MAP,
    WRITE_UPSERT,
    HTTP_CALL,
    MQ_PUBLISH,
    /**
     * 拆分（#140 / ADR-0017）：一条消息里的一个数组摊成 N 条子消息，是**基数变化**动作
     * （1 → N）。链上独立成动作，不挂在 FIELD_MAP 上——基数变化只能由路由层承担，字段映射是 1:1。
     */
    SPLIT
}
