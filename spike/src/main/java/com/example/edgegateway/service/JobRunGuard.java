package com.example.edgegateway.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务串行（#146）：同一个任务同一时刻最多一个运行。
 *
 * <p>判据是**进程内的运行中集合**，不查 {@code job_run} 里有没有 RUNNING 行：进程被强杀时那一行会
 * 永久留在 RUNNING，任务从此再也触发不了；内存集合随进程重启清空，是更安全的一侧。</p>
 *
 * <p><b>它保住的窗口</b>：从各触发入口 {@link #tryAcquire} 到 {@code finally} 里 {@link #release}。
 * 触发链是同步的（{@link JobTrigger#trigger} 用 {@code requestBody} 调 {@code direct:job-<id>}，
 * 整条路由跑完才返回），所以这个窗口恰好等于一次运行的持续区间——锁因此可以留在触发层，
 * 不必下沉进路由（路由的 {@code beginRun} 被「断点续跑」共用，放那里会连重投语义一起改掉）。</p>
 *
 * <p><b>已知限制</b>：互斥只在单进程内有效。多实例部署时两个网关进程各持一份集合，仍可能并发执行
 * 同一任务；届时需改为数据库锁或共享锁，另开票。</p>
 *
 * <p>本类刻意不依赖任何 mapper 或 service：四个入口都注入它，带上依赖会绕出循环。</p>
 */
@Component
public class JobRunGuard {

    /** 手动执行（{@code POST /api/jobs/{id}/execute}）。 */
    public static final String ORIGIN_MANUAL = "manual";
    /** 外部 API Key 触发（{@code POST /api/external/jobs/{id}/trigger}）。 */
    public static final String ORIGIN_EXTERNAL = "external";
    /** 定时调度触发。 */
    public static final String ORIGIN_SCHEDULE = "schedule";
    /** 消息重投（历史页那一个入口）。 */
    public static final String ORIGIN_REDELIVER = "redeliver";

    /** jobId → 当前占用者。值即那次占用的凭据，释放时必须原样交回。 */
    private final Map<Long, Handle> running = new ConcurrentHashMap<>();

    /**
     * 本次触发线程手上的占用凭据。触发链是同步的（同一个线程一路跑到路由里的 {@code beginRun}），
     * 靠它把「这次运行是谁占用的」带到簿记那一层，而不必给每个路由片段加构造参数。
     *
     * <p>只有**拿到**占用的线程才写它（{@link #tryAcquire}），被拒绝的线程不覆盖占用者的凭据；
     * {@link #release} 只清自己那一份。</p>
     */
    private final ThreadLocal<Handle> current = new ThreadLocal<>();

    /**
     * 占住某个任务。返回的 {@link Handle} 就是这次占用的凭据，释放时原样交回。
     *
     * <p>用 {@code putIfAbsent} 一次判定与占位（不要写成 {@code containsKey} + {@code put} 两拍：
     * 那本身就是竞态）。已有占用者时返回 {@code null}——调用方拿 {@link #current(Long)} 取当前
     * 占用者，把「正在跑的是哪一次运行」写进跳过日志。</p>
     */
    public Handle tryAcquire(Long jobId, String origin) {
        Handle handle = new Handle(jobId, origin, LocalDateTime.now());
        if (running.putIfAbsent(jobId, handle) != null) {
            return null;
        }
        current.set(handle);
        return handle;
    }

    /**
     * 释放这次占用。两参重载（{@code remove(key, value)}）保证只释放**自己**那一次：
     * 前一次运行的 {@code finally} 迟到时，不会把后一次运行刚拿到的占用删掉。
     */
    public void release(Handle handle) {
        if (handle != null) {
            running.remove(handle.jobId(), handle);
        }
        if (current.get() == handle) {
            current.remove();
        }
    }

    /**
     * 某个任务当前的占用者；没有人在跑时为 {@code null}。
     *
     * <p>给「已有运行、本次跳过」那条日志用：{@code tryAcquire} 返回 {@code null} 之后，
     * 调用方靠它拿到正在跑的那次运行（运行标识见 {@link #describe(Handle)}，由
     * {@link #bindRun} 在运行行建好后回填）。</p>
     */
    public Handle current(Long jobId) {
        return running.get(jobId);
    }

    /** 本线程手上的占用凭据；没占用（或未走互斥的路径）时为 {@code null}。 */
    public Handle threadHandle() {
        return current.get();
    }

    /**
     * 把本次运行的运行标识回填到这次占用上，只为让「跳过」那条日志带上当前 runId
     * （见 #146 定稿）；判据本身不依赖它，没回填时日志里不写这一格。
     *
     * <p><b>声明为静态是有意的</b>：簿记那一层（{@code StepLedger.beginRun}）必须能带着
     * {@code null} 调用它——不跑 Spring 的便捷构造路径（部分单测）没有守卫实例，
     * 那些路径本来也不走互斥，回填直接跳过。</p>
     *
     * <p>回填要求「这次占用的凭据正握在当前线程手上」，所以走到它必须与触发同线程。触发链是同步的
     * （{@link JobTrigger#trigger} 里 {@code requestBody} 跑完才返回），这条成立；哪天把执行改到
     * 线程池上，回填会静默失效——跳过日志那时就只有 jobId 与开始时刻（{@link #describe(Handle)} 把
     * 运行标识写成 {@code run=-}），判据本身不受影响。</p>
     */
    public static void bindRun(JobRunGuard guard, Long jobId, Long runId) {
        if (guard == null || jobId == null || runId == null) {
            return;
        }
        Handle handle = guard.running.get(jobId);
        if (handle != null && handle == guard.current.get()) {
            handle.bindRun(runId);
        }
    }

    /**
     * 一次占用的可读描述，给日志用：{@code job=7 origin=manual run=2837 startedAt=…}。
     *
     * <p>运行标识那一格在运行行建好之前是空的（{@link #bindRun} 回填之后才有）——所以第一次
     * 触发时它可能显示为 {@code run=-}，被拒绝的那一方看到的一定是已经跑起来的那次。</p>
     */
    public String describe(Handle handle) {
        if (handle == null) {
            return "未被占用";
        }
        return "job=" + handle.jobId()
                + " origin=" + handle.origin()
                + " run=" + (handle.runId() == null ? "-" : handle.runId())
                + " startedAt=" + handle.startedAt();
    }

    /** 任务当前是否在运行。 */
    public boolean isRunning(Long jobId) {
        return running.containsKey(jobId);
    }

    /**
     * 一次占用的凭据。{@code runId} 在运行行创建后回填（见 {@link #bindRun}），所以声明为 volatile：
     * 回填发生在触发线程、读取可能发生在调度线程。
     */
    public static final class Handle {

        private final Long jobId;
        private final String origin;
        private final LocalDateTime startedAt;
        private volatile Long runId;

        Handle(Long jobId, String origin, LocalDateTime startedAt) {
            this.jobId = jobId;
            this.origin = origin;
            this.startedAt = startedAt;
        }

        void bindRun(Long runId) {
            this.runId = runId;
        }

        public Long jobId() {
            return jobId;
        }

        public String origin() {
            return origin;
        }

        public LocalDateTime startedAt() {
            return startedAt;
        }

        public Long runId() {
            return runId;
        }
    }
}
