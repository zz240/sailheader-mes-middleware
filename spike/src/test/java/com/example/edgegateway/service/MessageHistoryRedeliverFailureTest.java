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
import com.example.edgegateway.model.MessageStep;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #108：断点重投必须"失败就是失败"。
 *
 * <p>这条用例跑的是**真实 Camel 断点续跑路由**（不是 mock 的 ProducerTemplate）：投递走
 * {@code direct:job-N-resume}，续跑失败时 exchange 上挂着异常，而 {@code producer.request(uri, processor)}
 * 这个重载**不重抛**它——于是重投照样返回成功、原步被标 {@code REDELIVERED}，运维据此收工，
 * 而目标端根本没拿到数据（实测 run 301/302）。</p>
 *
 * <p>对照：{@code MessageHistoryServiceTest} 里 mock 掉 ProducerTemplate 的用例把
 * {@code producer.request(...)} 桩成抛异常，恰好掩盖了这个重载的真实语义。</p>
 */
class MessageHistoryRedeliverFailureTest {

    @BeforeAll
    static void initEntityTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageStep.class);
        TableInfoHelper.initTableInfo(assistant, JobRun.class);
        TableInfoHelper.initTableInfo(assistant, Action.class);
        TableInfoHelper.initTableInfo(assistant, Job.class);
    }

    private final DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);

    private final Map<Long, JobRun> runs = new ConcurrentHashMap<>();
    private final Map<Long, MessageStep> steps = new ConcurrentHashMap<>();

    private long runSeq = 1;
    private long stepSeq = 1;

    /** 依赖未恢复时重投：抛"重投失败：<原因>"，且**不改**原步状态（原步仍 FAILED）。 */
    @Test
    void failingResumeKeepsStepFailedAndThrows() throws Exception {
        try (Harness h = harness(31L)) {
            long stepId = h.failedStep(3, "{\"order_no\":\"PO-31\"}");
            h.stopTarget(); // 依赖"还没修好"

            assertThatThrownBy(() -> h.service.redeliver(stepId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("重投失败")
                    .as("前缀只加一次：不该出现『重投失败: 重投失败: …』")
                    .hasMessageNotContaining("重投失败: 重投失败");

            assertThat(steps.get(stepId).getStatus())
                    .as("重投失败后原步必须保持 FAILED | steps=" + describeSteps())
                    .isEqualTo("FAILED");
            verify(messageStepMapper, never()).updateById(any(MessageStep.class));
        }
    }

    /** 验收标准对照项：依赖恢复后重投成功，原步 `REDELIVERED`（现状正确，不得回归）。 */
    @Test
    void succeedingResumeMarksStepRedelivered() throws Exception {
        try (Harness h = harness(32L)) {
            long stepId = h.failedStep(3, "{\"order_no\":\"PO-32\"}");

            MessageStep result = h.service.redeliver(stepId);

            assertThat(result.getStatus()).isEqualTo("REDELIVERED");
            assertThat(steps.get(stepId).getStatus()).isEqualTo("REDELIVERED");
            assertThat(runs.values()).anyMatch(r -> "SUCCESS".equals(r.getStatus()));
        }
    }

    private String describeSteps() {
        return steps.values().stream()
                .map(s -> s.getRunId() + ":" + s.getStep() + "/" + s.getStatus() + "/" + s.getError())
                .toList().toString();
    }

    /**
     * 起一条真实链：MQ_CONSUME(1) → FIELD_MAP(2) → HTTP_CALL(3, 目标端 = 本地 mock)。
     * 停掉 mock 即"依赖不可用"（连接被拒），起回来即"已修复"。
     */
    private Harness harness(long jobId) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = new MessageHistoryService(
                jobRunMapper, messageStepMapper, jobMapper, actionMapper, new ObjectMapper(), context,
                new JobLastRunWriter(jobMapper));
        bindInMemory();
        Job job = new Job();
        job.setId(jobId);
        job.setStatus(1);
        job.setRetryCount(0);
        job.setBackoffMs(1L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", 1L, "queue", "mes.to.edge")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))),
                action(3, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/receive", "method", "POST")));
        DataSource mq = new DataSource();
        mq.setId(1L);
        mq.setType(DataSourceType.RABBITMQ);
        mq.setName("mes.exchange");
        mq.setAddition(Map.of("host", "localhost", "port", 5672));
        when(dataSourceMapper.selectById(1L)).thenReturn(mq);

        HttpServer mes = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mes.createContext("/receive", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mes.start();
        DataSource http = new DataSource();
        http.setId(2L);
        http.setType(DataSourceType.HTTP);
        http.setAddress("http://127.0.0.1:" + mes.getAddress().getPort());
        http.setAddition(Map.of());
        when(dataSourceMapper.selectById(2L)).thenReturn(http);

        RouteGenerator generator = new RouteGenerator(
                dataSourceMapper, mock(WatermarkStore.class), history, mock(DataSourceManager.class),
                new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        // 摘掉 MQ 消费路由，只留重放路由与断点续跑路由
        context.removeRouteDefinition(context.getRouteDefinition("job-" + jobId));
        context.start();
        when(jobMapper.selectById(jobId)).thenReturn(job);
        return new Harness(context, history, mes, jobId);
    }

    /** 上下文 + 常用夹具：真实链、可控目标端、造一条 FAILED 消息步。 */
    private final class Harness implements AutoCloseable {

        private final DefaultCamelContext context;
        private final MessageHistoryService service;
        private final HttpServer target;
        private final long jobId;

        private Harness(DefaultCamelContext context, MessageHistoryService service, HttpServer target, long jobId) {
            this.context = context;
            this.service = service;
            this.target = target;
            this.jobId = jobId;
        }

        /** 让第 3 步（HTTP_CALL）的目标端不可用：连接被拒 → 重投必然失败。 */
        private void stopTarget() {
            target.stop(0);
        }

        private long failedStep(int step, String inputSnapshot) {
            JobRun failedRun = new JobRun();
            failedRun.setId(runSeq++);
            failedRun.setJobId(jobId);
            failedRun.setStatus("FAILED");
            runs.put(failedRun.getId(), failedRun);
            MessageStep failed = new MessageStep();
            failed.setId(stepSeq++);
            failed.setRunId(failedRun.getId());
            failed.setMessageId("msg-f");
            failed.setStep(step);
            failed.setActionName("call");
            failed.setStatus("FAILED");
            failed.setInputSnapshot(inputSnapshot);
            steps.put(failed.getId(), failed);
            return failed.getId();
        }

        @Override
        public void close() throws Exception {
            context.stop();
            target.stop(0);
        }
    }

    private void bindInMemory() {
        when(jobRunMapper.insert(any(JobRun.class))).thenAnswer(inv -> {
            JobRun run = inv.getArgument(0);
            run.setId(runSeq++);
            runs.put(run.getId(), run);
            return 1;
        });
        when(jobRunMapper.selectById(anyLong())).thenAnswer(inv -> runs.get((Long) inv.getArgument(0)));
        when(jobRunMapper.updateById(any(JobRun.class))).thenAnswer(inv -> {
            JobRun run = inv.getArgument(0);
            runs.put(run.getId(), run);
            return 1;
        });
        when(jobRunMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(runs.values()));
        when(messageStepMapper.insert(any(MessageStep.class))).thenAnswer(inv -> {
            MessageStep step = inv.getArgument(0);
            step.setId(stepSeq++);
            steps.put(step.getId(), step);
            return 1;
        });
        when(messageStepMapper.selectById(anyLong())).thenAnswer(inv -> steps.get((Long) inv.getArgument(0)));
        when(messageStepMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(steps.values()));
        when(messageStepMapper.updateById(any(MessageStep.class))).thenAnswer(inv -> {
            MessageStep step = inv.getArgument(0);
            steps.put(step.getId(), step);
            return 1;
        });
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setName(switch (kind) {
            case FIELD_MAP -> "map";
            case HTTP_CALL -> "call";
            default -> "consume";
        });
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }
}
