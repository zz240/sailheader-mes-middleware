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
     * 一分多（1 → N）：一条消息按配置摊成 N 条，两档由 {@code config/OneToManyConfig} 的 {@code mode}
     * 决定（拆分档造 N 条新消息；还原档把出站响应还回原来那几条消息）。
     *
     * <p>两档的外形是同一个骨架（取来源里的一片数组 → 逐条产生一条消息 → 每条记一行消息步），
     * 差别只在「这一条的身份从哪来」与「输出的 body 怎么拼」：</p>
     * <ul>
     *   <li><b>拆分档</b>：造**新**消息（新标识、业务键取自元素、换批号），元素按顺序摊开、不配对；</li>
     *   <li><b>还原档</b>：把出站拿回的响应按业务键配对，**还回原来那几条**（标识 / 业务键 / 父消息标识 /
     *       批内序号 / 批号全部沿用、不换号），要求上游链上有**合并**动作（成员清单是唯一的联系）。</li>
     * </ul>
     *
     * <p>链上独立成动作、不挂在 FIELD_MAP 上：基数变化只能由路由层承担，字段映射是 1:1。
     * 库里既有的动作行由迁移 {@code V7__one_to_many_action.sql} 归一到本种类 + 对应的 {@code mode}。</p>
     */
    ONE_TO_MANY,
    /**
     * 合并（#142 / ADR-0017）：攒住一批消息（边界 = 上游读取批读完那一刻），**打成一条报文**（N → 1）。
     * **它不认识出口**——发去哪儿由链上后面的 {@code HTTP_CALL} / {@code MQ_PUBLISH} 决定
     * （ADR-0017 决策 6 的修订：早先让它自己发请求，把出口的种类与数量钉死在了这一个动作上）。
     */
    AGGREGATE
}
