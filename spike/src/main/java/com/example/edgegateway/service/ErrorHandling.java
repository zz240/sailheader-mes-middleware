package com.example.edgegateway.service;

import com.example.edgegateway.model.Job;
import org.apache.camel.builder.RouteBuilder;

/**
 * 全链失败面（#145 从 {@code RouteGenerator} 搬出，一字未改）：一条路由的 {@code onException} 配置。
 *
 * <p>三条口径：</p>
 * <ol>
 *   <li><b>重试与退避</b>取任务上的 {@code retryCount} / {@code backoffMs}（缺省 0 次 / 1000ms）。</li>
 *   <li><b>尝试次数自计数</b>：{@code onExceptionOccurred} 每次投递失败都 +1（含最后一次）。不读 Camel 的
 *       {@code CamelRedeliveryCounter}——它写在 message header 上，每次重投会被 remove/decrement，
 *       读它恒为 {@code null + 1}（#109）。</li>
 *   <li><b>退避不计入动作耗时</b>（#134）：{@code onRedelivery} 里重打计时起点。</li>
 * </ol>
 *
 * <p>{@code handled} 由入口决定：消费主路由 {@code true}（失败后链还会往下走、进程不退），
 * 重放 / 轮询 / 断点续跑 / 打包重发都是 {@code false}（异常抛回调用方）。</p>
 */
final class ErrorHandling {

    private ErrorHandling() {
    }

    static void configure(RouteBuilder builder, Job job, boolean handled, StepLedger ledger) {
        int retries = job.getRetryCount() == null ? 0 : job.getRetryCount();
        long backoff = job.getBackoffMs() == null ? 1000L : job.getBackoffMs();
        builder.onException(Exception.class)
                .maximumRedeliveries(retries)
                .redeliveryDelay(backoff)
                .onExceptionOccurred(ledger::noteAttemptFailure)
                .onRedelivery(ledger::restartStepTiming)
                .process(ledger::recordFailure)
                .handled(handled);
    }
}
