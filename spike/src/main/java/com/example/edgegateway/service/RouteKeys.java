package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;

/**
 * 路由运行期挂在 exchange 上的**属性键**（#145）：装配、片段、簿记三边共用的契约。
 *
 * <p>这一组名字是**跨类、跨运行期**的接口，不是实现细节：断点续跑按它们回填上下文、消息历史的批次
 * 分组按它们取值、出站与还原按 {@link #BATCH_MANIFEST} 交接一整批。**一个名字都不能改**（改了要让
 * 续投、历史页、批次分组跟着一起改）。</p>
 *
 * <p>放在这里的只有「原本就近定义在 {@code RouteGenerator} 里」的那几个；会话、消息标识那一批
 * 本来就由 {@link MessageHistoryService} 持有（它的常量是唯一定义处），这里只做**同名转发**，
 * 省得每个片段类各自 import 一串、也避免同一个键在两处各写一遍字符串。</p>
 */
final class RouteKeys {

    private RouteKeys() {
    }

    // ---- 任务上下文与断点续跑 ----

    /** 任务上下文：{@code context[动作序号] = 该步输出}，链上"当前数据"是 {@code context[0]}。 */
    static final String CONTEXT = "edge.context";
    /** 出站动作这一次**被断点续投跳过**了：片段里那层 choice 据此不发请求（但照样记行）。 */
    static final String SKIP_FLAG = "edge.skipFragment";

    // ---- 消息步 / 运行簿记 ----

    /** 某个步骤已失败了几次（#109）：onExceptionOccurred 自计数，写 {@code message_step.attempt} 时读它。 */
    static final String FAILED_ATTEMPTS = "edge.failedAttempts";
    /** {@link #FAILED_ATTEMPTS} 是哪个步骤的失败次数——读的时候要对上号，否则会串到别的步骤头上。 */
    static final String FAILED_ATTEMPTS_STEP = "edge.failedAttemptsStep";
    /**
     * 本步动作**开始执行**的时刻（epoch 毫秒，#134）。落库那一刻与它相减，得到这一步的
     * {@code duration_ms}——量的是动作本身，不是相邻消息步的间隔（含排队与开销，另一个量）。
     */
    static final String STEP_STARTED_AT = "edge.stepStartedAt";
    /**
     * 已经**结算好**的本步动作耗时（#134）。READ_SQL 的执行在 split 之前就结束了、落库却发生在每条源行上，
     * 所以在 split 之前结算一次，各源行共用这一个数（split 会把 exchange 属性一并拷给子 exchange）——
     * 不这么做，第二条源行量到的就是「读库 + 前一条消息跑完」的时长。
     */
    static final String STEP_DURATION_MS = "edge.stepDurationMs";

    // ---- 读库分批与水位 ----

    /** 当前批次下界（epoch 毫秒 / 自增值），每批处理后更新；持久化用 {@link WatermarkStore}。 */
    static final String WATERMARK = "edge.readWatermark";
    /** 是否还有下一批（keyset 分页未扫完）。 */
    static final String HAS_MORE = "edge.hasMoreBatches";

    // ---- 基数变化（拆分 / 合并 / 还原）----

    /**
     * 拆分这一批的**来源对象**（#140）：{@code keepParent} 要摊平的是**父消息**的外层字段，
     * 而父消息是按配置（sourceStep + sourcePath）取到的那个对象——不是数组里的某个元素。
     */
    static final String SPLIT_SOURCE = "edge.splitSource";
    /**
     * 这一行**本该**摊出几条消息（#142）：读库那批一行一条（没人写它，缺省 1），拆分动作在 split
     * 之前改写成摊出来的条数。合并动作靠它判「这一批齐了没有」——按行去重后合计，所以同一行拆出来的
     * k 条带的是同一个数（去重靠父消息标识）。
     */
    static final String BATCH_EXPECTED = "edge.batchExpected";
    /**
     * 合并动作攒到的那批**成员清单**（#142）：每条只有身份与它自己那一片。
     * 名字的唯一定义在 {@link MessageHistoryService#BATCH_MANIFEST}——还原与出站失败时的补记
     * 都要用它。**不进报文、不进快照**（绝不能污染发给终端的 JSON）。
     */
    static final String BATCH_MANIFEST = MessageHistoryService.BATCH_MANIFEST;
    /**
     * 这一批是**空的**（#142）：读库读到 0 行、或整批被断点续投跳过。
     * 空批不能往下走——不然每个「没有新数据」的运行都会朝目标端发一份空报文。
     */
    static final String BATCH_EMPTY = "edge.batchEmpty";
}
