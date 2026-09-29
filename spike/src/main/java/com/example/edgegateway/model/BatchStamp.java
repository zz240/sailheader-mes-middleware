package com.example.edgegateway.model;

/**
 * 一条消息步上的**批次戳**（#139 / ADR-0017）：这一行属于哪一批、批里第几条、从哪条消息来。
 *
 * <p>三格一起走，是因为它们同属「写一条消息步时顺带记下的身份」，而不是一件事：</p>
 * <ul>
 *   <li>{@code batchId} 管**分组**——某一个动作上、被同一次基数变化动作归到一起的那组消息的编号
 *       （每执行一次现生成，随消息在链上继承到下一个基数变化动作为止）；</li>
 *   <li>{@code batchSeq} 管**批内顺序**——拆分的元素顺序 / 合并的攒批顺序，从 1 起；
 *       **只在拆分与合并时写**，读取批留空（空不是 0）；</li>
 *   <li>{@code parentMessageId} 管**谱系**——这条消息是从哪条消息拆出来的，与哪一次执行无关。</li>
 * </ul>
 *
 * <p>{@code parentMessageId} 不能反推分组（一条父消息被拆两次时两次的子消息这一格完全相同，
 * 只有批号分得开），所以两格都不省（ADR-0017 决策 4）。三格都可空：MQ 消费 / 任务输入那些链
 * 根本没有批号，读取批没有批内序号，而非拆分来的消息也没有父消息。</p>
 */
public record BatchStamp(String batchId, Integer batchSeq, String parentMessageId) {

    /** 这一行不属于任何一批、也不是拆出来的（MQ 消费 / 任务输入 / 读取批的那些行）。 */
    public static final BatchStamp NONE = new BatchStamp(null, null, null);
}
