package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #109：{@code message_step.attempt} 必须是**真实总尝试次数**（失败即停的重试退避在同一 run 内投递 N 次）。
 *
 * <p>原实现读 exchange property {@code CamelRedeliveryCounter}，而 Camel 4 把它写进 message header
 * （且 {@code handled=true} 的路由进失败处理器前会 remove、{@code handled=false} 的路由先 -1）→
 * 表达式恒为 {@code null + 1}，落库永远是 1：历史页最常见的排查问题"这条是第一次就成功、还是重试才成功"
 * 因此无法回答。</p>
 *
 * <p>链：MQ_CONSUME(1) → FIELD_MAP(2) → HTTP_CALL(3)，目标端是本地 mock（可控先 500 后 200），
 * 两条入口都跑：重放入口（{@code handled=false}，失败抛回调用方）与消费主路由（{@code handled=true}，
 * rabbit 入口换成 direct 触发）。</p>
 */
class RouteGeneratorAttemptTest {

    private final DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);
    private final AtomicLong runSeq = new AtomicLong(1);
    private final AtomicInteger jobSeq = new AtomicInteger(100);

    /** 重试耗尽仍失败 → 失败步 attempt = retryCount + 1（首跑 + 2 次重试 = 3）。 */
    @Test
    void failedStepRecordsTotalAttemptsIncludingRetries() throws Exception {
        Chain chain = run(2, Integer.MAX_VALUE, false);

        assertThat(chain.httpCalls()).as("目标端实际收到几次（首跑 + 2 次重试）| " + chain.history()).isEqualTo(3);
        Recorded failure = onlyFailure(chain.history());
        assertThat(failure.step()).isEqualTo(3);
        assertThat(failure.attempt()).isEqualTo(3);
        assertThat(onlySuccessOfStep(chain.history(), 2).attempt())
                .as("同一 run 内先成功的步骤只试了 1 次").isEqualTo(1);
    }

    /** retryCount=0 → attempt = 1（没有重试时不虚报）。 */
    @Test
    void failedStepWithoutRetriesRecordsSingleAttempt() throws Exception {
        Chain chain = run(0, Integer.MAX_VALUE, false);

        assertThat(chain.httpCalls()).isEqualTo(1);
        assertThat(onlyFailure(chain.history()).attempt()).isEqualTo(1);
    }

    /** 重试一次后成功 → SUCCESS 步 attempt = 2。 */
    @Test
    void stepSucceedingAfterOneRetryRecordsTwoAttempts() throws Exception {
        Chain chain = run(2, 1, false); // 第 2 次调用开始返回 200

        assertThat(chain.httpCalls()).as("首跑失败 + 第 2 次成功").isEqualTo(2);
        assertThat(chain.history().failures).as("重试后成功不该留 FAILED 步").isEmpty();
        assertThat(onlySuccessOfStep(chain.history(), 3).attempt()).isEqualTo(2);
    }

    /** 消费主路由（handled=true）：失败不抛回 broker，attempt 同样要准。 */
    @Test
    void failedStepOnHandledConsumerRouteRecordsTotalAttempts() throws Exception {
        Chain chain = run(2, Integer.MAX_VALUE, true);

        assertThat(chain.httpCalls()).as("目标端实际收到几次（首跑 + 2 次重试）| " + chain.history()).isEqualTo(3);
        assertThat(onlyFailure(chain.history()).attempt()).isEqualTo(3);
    }

    /**
     * 串味防线：前一个步骤重试过，**后一个步骤一次成功**时不能被算成"重试过的"。
     * 入口链 `handled=true` 失败后链还会往下走，所以这个组合是真实存在的。
     * 链：MQ_CONSUME(1) → FIELD_MAP(2) → HTTP_CALL(3, 重试 2 次才成) → FIELD_MAP(4, 一次成)。
     */
    @Test
    void laterStepSucceedingFirstTryKeepsAttemptOneAfterEarlierStepRetried() throws Exception {
        Chain chain = run(2, 2, true, actionsWithTrailingMap());

        assertThat(chain.httpCalls()).as("步骤 3 首跑失败 + 2 次重试才成功").isEqualTo(3);
        assertThat(chain.history().failures).isEmpty();
        assertThat(onlySuccessOfStep(chain.history(), 3).attempt())
                .as("步骤 3 试了 3 次才成功").isEqualTo(3);
        assertThat(onlySuccessOfStep(chain.history(), 4).attempt())
                .as("步骤 4 一次成功，不该背上步骤 3 的重试").isEqualTo(1);
    }

    /** #134 成功面：跑完一条成功链，落库的每一步都要带动作耗时（空的那些算没测出来）。 */
    @Test
    void successfulStepsRecordNonNullDuration() throws Exception {
        Chain chain = run(0, 0, false);

        assertThat(chain.history().successes)
                .as("成功链每一步都要有耗时 | " + chain.history())
                .isNotEmpty()
                .allMatch(r -> r.durationMs() != null);
    }

    /**
     * #134 失败面：失败那一步也记耗时，而且记的是**最后一次尝试的动作执行**，不是整段（含退避）。
     *
     * <p>退避故意设成 300 ms、重试 2 次：把退避算进去的话这个数会 ≥ 600 ms。这条是 ADR-0016
     * 决策 7 那条口径（动作耗时 ≠ 步间隔）在链路上的钉子。</p>
     */
    @Test
    void failedStepDurationExcludesRetryBackoff() throws Exception {
        Chain chain = run(2, Integer.MAX_VALUE, false, actions(), 300L);

        Recorded failure = onlyFailure(chain.history());
        assertThat(failure.durationMs()).as("失败步也要有耗时 | " + chain.history()).isNotNull();
        assertThat(failure.durationMs())
                .as("含两次 300 ms 退避的话这里会 ≥ 600 ms | " + chain.history())
                .isLessThan(300L);
    }

    // ---------- 夹具 ----------

    /**
     * 跑一条真实链并把 attempt 记下来。{@code failuresBeforeOk} 是目标端的失败次数
     * （HTTP 调用计数 ≤ 它时返回 500），用来制造"重试才成功"。
     */
    private Chain run(int retryCount, int failuresBeforeOk, boolean consumerRoute) throws Exception {
        return run(retryCount, failuresBeforeOk, consumerRoute, actions(), 1L);
    }

    private Chain run(int retryCount, int failuresBeforeOk, boolean consumerRoute, List<Action> actions)
            throws Exception {
        return run(retryCount, failuresBeforeOk, consumerRoute, actions, 1L);
    }

    private Chain run(int retryCount, int failuresBeforeOk, boolean consumerRoute, List<Action> actions,
                      long backoffMs) throws Exception {
        Job job = job(jobSeq.incrementAndGet(), retryCount, backoffMs);
        AtomicInteger httpCalls = new AtomicInteger();
        RecordingHistory history = new RecordingHistory();
        try (Target target = new Target(httpCalls, failuresBeforeOk)) {
            when(dataSourceMapper.selectById(1L)).thenReturn(rabbitDataSource());
            when(dataSourceMapper.selectById(2L)).thenReturn(target.dataSource());
            stubRunInsert();

            DefaultCamelContext context = new DefaultCamelContext();
            RouteGenerator generator = new RouteGenerator(
                    dataSourceMapper, mock(WatermarkStore.class), history, mock(DataSourceManager.class),
                    new ReadSqlBatcher());
            for (var builder : generator.build(job, actions)) {
                context.addRoutes(builder);
            }
            String trigger = "direct:job-" + job.getId();
            if (consumerRoute) {
                // 消费主路由换成 direct 触发（不依赖 broker），handled=true 语义保持不变；advice 要在 start 之前
                AdviceWith.adviceWith(context, "job-" + job.getId(),
                        a -> a.replaceFromWith("direct:consume-" + job.getId()));
                trigger = "direct:consume-" + job.getId();
            } else {
                // 摘掉 MQ 消费路由，只走重放路由（链首源步骤由重放入口承担）
                context.removeRouteDefinition(context.getRouteDefinition("job-" + job.getId()));
            }
            context.start();
            String uri = trigger;
            try {
                if (consumerRoute) {
                    // 消费入口先 unmarshal().json()：body 要是 JSON 串（真实 broker 投递的就是串）
                    context.createProducerTemplate().requestBody(uri, "{\"orderNo\":\"PO-1\"}");
                } else if (failuresBeforeOk == Integer.MAX_VALUE) {
                    // handled=false + 重试耗尽 → 异常抛回调用方（attempt 已在失败处理器里记下）
                    assertThatThrownBy(() -> context.createProducerTemplate()
                            .requestBody(uri, Map.of("orderNo", "PO-1")))
                            .as("失败链应向调用方抛异常").isInstanceOf(Exception.class);
                } else {
                    context.createProducerTemplate().requestBody(uri, Map.of("orderNo", "PO-1"));
                }
            } finally {
                context.stop();
            }
            return new Chain(history, httpCalls.get());
        }
    }

    private List<Action> actions() {
        return List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/receive", "method", "POST")));
    }

    /** 在上面那条链后再接一个 FIELD_MAP(4)：用来验"重试过的步骤"不会把计数漏给后面的步骤。 */
    private List<Action> actionsWithTrailingMap() {
        List<Action> actions = new ArrayList<>(actions());
        actions.add(action(4, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                Map.of("sourceStep", 2, "source", "order_no", "target", "order_no_copy")))));
        return List.copyOf(actions);
    }

    /** beginRun 依赖 insert 回填的自增 id：没有 runId 就会静默不记消息步（记录桩要装得像真的）。 */
    private void stubRunInsert() {
        when(jobRunMapper.insert(ArgumentMatchers.any(JobRun.class))).thenAnswer(inv -> {
            JobRun run = inv.getArgument(0);
            run.setId(runSeq.getAndIncrement());
            return 1;
        });
    }

    private DataSource rabbitDataSource() {
        DataSource mq = new DataSource();
        mq.setId(1L);
        mq.setType(DataSourceType.RABBITMQ);
        mq.setName("mes.exchange");
        mq.setAddition(Map.of("host", "localhost", "port", 5672));
        return mq;
    }

    private Job job(long id, int retryCount, long backoffMs) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(1);
        job.setRetryCount(retryCount);
        job.setBackoffMs(backoffMs);
        return job;
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setName(switch (kind) {
            case FIELD_MAP -> "map";
            case HTTP_CALL -> "call";
            case MQ_CONSUME -> "consume";
            default -> kind.name();
        });
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }

    private Recorded onlyFailure(RecordingHistory history) {
        assertThat(history.failures).as("应只记一条 FAILED 消息步").hasSize(1);
        return history.failures.getFirst();
    }

    private Recorded onlySuccessOfStep(RecordingHistory history, int step) {
        List<Recorded> matched = history.successes.stream().filter(r -> r.step() == step).toList();
        assertThat(matched).as("步骤 " + step + " 应只有一条 SUCCESS 消息步 | " + history.successes).hasSize(1);
        return matched.getFirst();
    }

    private record Recorded(String messageId, int step, String status, int attempt, Long durationMs) {
    }

    /** 一次真实链的结果：记下的消息步 + 目标端实际收到几次。 */
    private record Chain(RecordingHistory history, int httpCalls) {
    }

    /** 目标端：前 {@code failuresBeforeOk} 次调用返回 500，其后 200——用来制造"重试一次才成功"。 */
    private static final class Target implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger calls;

        private Target(AtomicInteger calls, int failuresBeforeOk) throws Exception {
            this.calls = calls;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/receive", exchange -> {
                int n = calls.incrementAndGet();
                byte[] body = ("call#" + n).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(n <= failuresBeforeOk ? 500 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        private DataSource dataSource() {
            DataSource ds = new DataSource();
            ds.setId(2L);
            ds.setType(DataSourceType.HTTP);
            ds.setAddress("http://127.0.0.1:" + server.getAddress().getPort());
            ds.setAddition(Map.of());
            return ds;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** 只截获"写了什么 attempt"：其余依赖不参与本用例。 */
    private final class RecordingHistory extends MessageHistoryService {

        private final List<Recorded> successes = new ArrayList<>();
        private final List<Recorded> failures = new ArrayList<>();

        private RecordingHistory() {
            super(jobRunMapper, messageStepMapper, jobMapper, actionMapper, new ObjectMapper(),
                    new DefaultCamelContext(), new JobLastRunWriter(jobMapper));
        }

        @Override
        public void recordSuccess(Long runId, String messageId, Integer step, String actionName,
                                  Object input, Object data, String businessKey, int attempt, Long durationMs) {
            successes.add(new Recorded(messageId, step, "SUCCESS", attempt, durationMs));
        }

        @Override
        public void recordFailure(Long runId, String messageId, Integer step, String actionName,
                                  String error, Object input, String businessKey, int attempt, Long durationMs) {
            failures.add(new Recorded(messageId, step, "FAILED", attempt, durationMs));
        }

        @Override
        public String toString() {
            return "successes=" + successes + " failures=" + failures;
        }
    }
}
