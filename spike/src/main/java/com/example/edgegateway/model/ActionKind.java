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
    SPLIT,
    /**
     * 合并（#142 / ADR-0017）：攒住一批消息（边界 = 上游读取批读完那一刻），**打成一条报文**（N → 1）。
     * **它不认识出口**——发去哪儿由链上后面的 {@code HTTP_CALL} / {@code MQ_PUBLISH} 决定
     * （ADR-0017 决策 6 的修订：早先让它自己发请求，把出口的种类与数量钉死在了这一个动作上）。
     */
    AGGREGATE,
    /**
     * 扇回（#142 / ADR-0017 决策 6）：把出站拿回来的响应按配对字段摊回**原来那 N 条消息**（1 → N）。
     *
     * <p>与 {@link #SPLIT} 形似而神不同：拆分会**新造**消息并**换批号**；扇回**回到原来那几条**
     * （消息标识 / 业务键 / 父消息标识 / 批内序号都沿用）、**不换号**。没有响应的出口（MQ 发布）
     * 就不摆这一档。</p>
     */
    FANOUT
}
