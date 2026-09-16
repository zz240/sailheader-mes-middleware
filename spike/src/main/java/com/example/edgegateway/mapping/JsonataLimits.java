package com.example.edgegateway.mapping;

/**
 * 表达式求值的资源上限：跑多久（超时）与递归多深（深度）——ADR-0012 决策 6 说的那套「受控边界」。
 *
 * <p>这个能力是**引擎自带的**（dashjoin 的 {@code Timebox}，JS 版那边叫 {@code setRuntimeBounds}；
 * {@code docs/research/mapping-framework.md} 记了「强制启用」却一直没接线）：每进/出一次求值节点问一次
 * 「跑了多久、递归多深」，越界就抛 {@code JException}。挂在一份**求值帧**上，所以每一份上限对应一次
 * 求值，映射执行器本身跨消息复用仍然是线程安全的（见 {@link JsonataFieldMapper}）。</p>
 *
 * <p>两条路共用同一份默认值：运行时（结构化规则与 {@code mode=EXPRESSION} 手写表达式）与编辑器的试算
 * 都从这里取——「试算用的边界」与「真跑用的边界」不是两套。</p>
 *
 * <p><b>深度为什么给到 10 万（而不是引擎默认的 100）</b>：这个计数器把**内置函数调用**也算进去
 * （实测：数组里每个元素挂一个 {@code $number}，深度就涨一层，与数组长度同阶）。卡到 100 那样会误伤
 * 正常的大数组映射——实测 200 个元素、子行里一个数字转换就撞上了「Stack overflow」；而 5000 个元素
 * 一层要 5000 层。真正拦住死循环的是**超时**：这个引擎里的死循环是尾递归，被优化成循环、深度不涨，
 * 超时必拦（实测 5 秒拦下）。深度这一条是兜底，只在真正病态的递归上才响。</p>
 *
 * @param timeoutMs 一次求值最多跑多少毫秒
 * @param maxDepth  最多递归多少层（数组迭代与内置函数调用都会计入）
 */
public record JsonataLimits(long timeoutMs, int maxDepth) {

    /**
     * 默认上限：5 秒、10 万层。
     *
     * <p>正常一条映射是毫秒级、深度是个位数；写坏了的表达式（死循环的尾递归、爆炸的 {@code $map}）
     * 跑不到把网关这一条消息卡住。</p>
     */
    public static final JsonataLimits DEFAULT = new JsonataLimits(5000L, 100_000);
}
