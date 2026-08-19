package com.example.edgegateway.model;

/** 动作种类（DS-8 语义化）：读写意图显式化，不再靠位置与 addition 内容猜测。 */
public enum ActionKind {
    MQ_CONSUME,
    READ_SQL,
    FIELD_MAP,
    WRITE_UPSERT,
    HTTP_CALL,
    MQ_PUBLISH
}
