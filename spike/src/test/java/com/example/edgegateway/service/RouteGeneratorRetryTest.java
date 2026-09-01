package com.example.edgegateway.service;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 RouteGenerator 使用的 onException 重试 DSL 模式：
 * maximumRedeliveries 按任务配置生效、重试耗尽后失败记录只执行一次、handled(false) 让调用方看到失败。
 * （与生成路由中的调用方式一致：maximumRedeliveries(retryCount).redeliveryDelay(backoff)）
 */
class RouteGeneratorRetryTest {

    private AtomicInteger attempts;
    private AtomicInteger failureRecords;
    private DefaultCamelContext context;

    private void startRoute(int retries, long delay) throws Exception {
        attempts = new AtomicInteger();
        failureRecords = new AtomicInteger();
        context = new DefaultCamelContext();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                onException(Exception.class)
                        .maximumRedeliveries(retries)
                        .redeliveryDelay(delay)
                        .process(exchange -> failureRecords.incrementAndGet())
                        .handled(false);
                from("direct:test")
                        .process(exchange -> {
                            attempts.incrementAndGet();
                            throw new IllegalStateException("boom");
                        });
            }
        });
        context.start();
    }

    @Test
    void retriesTwiceThenRecordsFailureOnceAndFails() throws Exception {
        startRoute(2, 1);
        try {
            assertThatThrownBy(() -> context.createProducerTemplate().requestBody("direct:test", "x"))
                    .isInstanceOf(Exception.class);
        } finally {
            context.stop();
        }
        assertThat(attempts.get()).isEqualTo(3); // 初始 1 次 + 2 次重试
        assertThat(failureRecords.get()).isEqualTo(1); // 重试耗尽后失败只记一次
    }

    @Test
    void noRetryWhenRetryCountZero() throws Exception {
        startRoute(0, 1);
        try {
            assertThatThrownBy(() -> context.createProducerTemplate().requestBody("direct:test", "x"))
                    .isInstanceOf(Exception.class);
        } finally {
            context.stop();
        }
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(failureRecords.get()).isEqualTo(1);
    }
}
