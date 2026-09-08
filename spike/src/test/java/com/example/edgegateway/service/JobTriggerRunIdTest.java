package com.example.edgegateway.service;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * #88 兜底判据（真 Camel）：触发失败时能否从失败 exchange 读到本次运行的 runId。
 * runId 非空 = 运行已创建、结果已由 finishRun 统一回写；null = 触发层失败（无消费者），需调用方兜底。
 */
class JobTriggerRunIdTest {

    private DefaultCamelContext context;
    private JobTrigger jobTrigger;

    @BeforeEach
    void setUp() {
        context = new DefaultCamelContext();
        jobTrigger = new JobTrigger(context);
    }

    @AfterEach
    void tearDown() throws Exception {
        context.stop();
    }

    @Test
    void failureAfterRunStartedCarriesRunId() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:job-1")
                        .process(ex -> ex.setProperty(MessageHistoryService.RUN_ID, 42L))
                        .process(ex -> {
                            throw new IllegalStateException("boom");
                        });
            }
        });
        context.start();

        Throwable failure = catchThrowable(() -> jobTrigger.trigger(1L, Map.of()));

        assertThat(failure).isNotNull();
        assertThat(JobTrigger.runIdOf(failure)).isEqualTo(42L);
    }

    /** 触发层失败（beginRun 之前就失败，exchange 上没有 runId）：调用方需兜底回写。 */
    @Test
    void failureBeforeRunStartedYieldsNoRunId() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:job-2")
                        .process(ex -> {
                            throw new IllegalStateException("boom before run");
                        });
            }
        });
        context.start();

        Throwable failure = catchThrowable(() -> jobTrigger.trigger(2L, Map.of()));

        assertThat(failure).isNotNull();
        assertThat(JobTrigger.runIdOf(failure)).isNull();
    }
}
