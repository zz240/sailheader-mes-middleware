package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * **打包重发**（#142 / ADR-0017 决策 7 的修订版）：一次点击 = 一次请求，认的是**出站那一步的摘要行**。
 *
 * <p>这里考的是那次出站与它带来的账：整批失败之后拿出站那一步的摘要行重发——替身端点**只多收一次**请求
 * （报文从摘要行的 `input_snapshot` 取回，配对字段从指向这个出口的**还原**那一档取），新运行里摘要行与
 * 这一批每条各记一行（成功），旧的那一批（摘要行 + 每条消息行）标 {@code REDELIVERED}；
 * 不该发的时候（不是摘要行 / 不是失败批 / 这一步不是出站动作）当场拒绝，一次请求都不发。</p>
 *
 * <p>路由是**真的**（RouteGenerator 建、Camel 跑），mappers 是桩——链上的合并/还原行为在
 * {@code AggregateActionExecutionTest} 里对着 H2 验。</p>
 */
class BatchRedeliverTest {

    private static final long JOB_ID = 61L;
    /** 摘要行落在**出站**那一步（HTTP_CALL）。 */
    private static final int OUTLET_STEP = 2;

    @BeforeAll
    static void initEntityTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageStep.class);
        TableInfoHelper.initTableInfo(assistant, JobRun.class);
        TableInfoHelper.initTableInfo(assistant, Action.class);
        TableInfoHelper.initTableInfo(assistant, Job.class);
    }

    private final DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);

    private final AtomicInteger httpCalls = new AtomicInteger();
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> httpResponse = new AtomicReference<>("[]");
    private final AtomicInteger httpStatus = new AtomicInteger(200);

    private final List<MessageStep> inserted = new ArrayList<>();
    private final List<MessageStep> updated = new ArrayList<>();
    private final List<JobRun> runs = new ArrayList<>();

    private HttpServer target;
    private DefaultCamelContext context;
    private long stepSeq = 1;
    private long runSeq = 1;

    @BeforeEach
    void setUp() throws Exception {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            httpCalls.incrementAndGet();
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = httpResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();

        DataSource http = new DataSource();
        http.setId(2L);
        http.setType(DataSourceType.HTTP);
        http.setAddress("http://127.0.0.1:" + target.getAddress().getPort());
        http.setAddition(Map.of());
        when(dataSourceMapper.selectById(2L)).thenReturn(http);

        // 链：合并(1) → 出站(2) → 还原(3)：摘要行在出站那一步，配对字段在还原那一档
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(1, ActionKind.AGGREGATE, Map.of("bodyField", "items")),
                action(2, ActionKind.HTTP_CALL, Map.of("dataSourceId", 2L, "path", "/register", "method", "POST")),
                action(3, ActionKind.ONE_TO_MANY, Map.of("mode", "restore", "sourceStep", 2, "resultKeyField", "code", "attachAs", "handle"))));

        Job job = new Job();
        job.setId(JOB_ID);
        job.setStatus(1);
        job.setRetryCount(0);
        when(jobMapper.selectById(JOB_ID)).thenReturn(job);

        JobRun oldRun = new JobRun();
        oldRun.setId(7L);
        oldRun.setJobId(JOB_ID);
        oldRun.setStatus("FAILED");
        when(jobRunMapper.selectById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            if (id.equals(7L)) {
                return oldRun;
            }
            return runs.stream().filter(run -> id.equals(run.getId())).findFirst().orElse(null);
        });
        when(jobRunMapper.insert(any(JobRun.class))).thenAnswer(inv -> {
            JobRun run = inv.getArgument(0);
            run.setId(runSeq++);
            runs.add(run);
            return 1;
        });
        when(jobRunMapper.updateById(any(JobRun.class))).thenAnswer(inv -> {
            runs.add(inv.getArgument(0));
            return 1;
        });
        when(messageStepMapper.insert(any(MessageStep.class))).thenAnswer(inv -> {
            MessageStep step = inv.getArgument(0);
            step.setId(stepSeq++);
            inserted.add(step);
            return 1;
        });
        when(messageStepMapper.updateById(any(MessageStep.class))).thenAnswer(inv -> {
            updated.add(inv.getArgument(0));
            return 1;
        });

        context = new DefaultCamelContext();
        for (RouteBuilder builder : new RouteGenerator(dataSourceMapper, new MemoryWatermarkStore(),
                service(), dataSourceManager, new ReadSqlBatcher()).build(job, actionMapper.selectList(null))) {
            context.addRoutes(builder);
        }
        context.start();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.stop();
        }
        if (target != null) {
            target.stop(0);
        }
    }

    @Test
    void oneClickSendsExactlyOneRequestAndMarksTheOldBatchRedelivered() {
        MessageStep summary = step(11L, 7L, "batch:B-1", OUTLET_STEP, "FAILED", null,
                "{\"items\":[{\"code\":\"PO-1\"},{\"code\":\"PO-2\"}]}");
        MessageStep first = step(12L, 7L, "msg-1", OUTLET_STEP, "FAILED", 1, "{\"code\":\"PO-1\"}");
        MessageStep second = step(13L, 7L, "msg-2", OUTLET_STEP, "FAILED", 2, "{\"code\":\"PO-2\"}");
        when(messageStepMapper.selectById(11L)).thenReturn(summary);
        when(messageStepMapper.selectList(any())).thenReturn(List.of(first, second));
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"}]");

        JobRun resent = service().redeliverBatch(11L);

        assertThat(httpCalls.get()).as("一次点击 = 一次请求").isEqualTo(1);
        assertThat(lastRequest.get())
                .as("发出去的是摘要行上存下来的**整份报文**")
                .isEqualTo("{\"items\":[{\"code\":\"PO-1\"},{\"code\":\"PO-2\"}]}");
        assertThat(resent.getStatus()).isEqualTo("SUCCESS");
        assertThat(inserted).as("新运行里：摘要行 + 这一批每条各一行").hasSize(3);
        assertThat(inserted).allSatisfy(row -> {
            assertThat(row.getRunId()).isEqualTo(resent.getId());
            assertThat(row.getStatus()).isEqualTo("SUCCESS");
            assertThat(row.getBatchId()).isEqualTo("B-1");
        });
        MessageStep newSummary = inserted.stream()
                .filter(row -> row.getMessageId().startsWith(MessageHistoryService.SUMMARY_MESSAGE_PREFIX))
                .findFirst().orElseThrow();
        assertThat(newSummary.getDataSnapshot()).as("摘要行存整份响应").isEqualTo(httpResponse.get());
        assertThat(newSummary.getBatchSeq()).isNull();
        assertThat(inserted).filteredOn(row -> !row.getMessageId().startsWith("batch:"))
                .allSatisfy(row -> assertThat(row.getDataSnapshot()).as("每条只存配给自己的那一份")
                        .contains(row.getBusinessKey()));
        assertThat(updated).as("旧的那一批（摘要行 + 每条消息行）标 REDELIVERED").hasSize(3);
        assertThat(updated).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo("REDELIVERED"));
        assertThat(updated).extracting(MessageStep::getId).containsExactly(11L, 12L, 13L);
    }

    @Test
    void aBatchThatFailedDownstreamCanStillBeResent() {
        // 出站成功、还原那一步配不上 → 摘要行自己是 SUCCESS，成员行在**还原那一步**（步骤 3）失败。
        // 重发仍然是「再打一次请求」：同一条目标端第二次可能回一份完整的响应
        MessageStep summary = step(11L, 7L, "batch:B-1", OUTLET_STEP, "SUCCESS", null,
                "{\"items\":[{\"code\":\"PO-1\"}]}");
        MessageStep failedAtRestore = step(21L, 7L, "msg-1", 3, "FAILED", 1, "{\"code\":\"PO-1\"}");
        when(messageStepMapper.selectById(11L)).thenReturn(summary);
        when(messageStepMapper.selectList(any())).thenReturn(List.of(summary, failedAtRestore));
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"}]");

        JobRun resent = service().redeliverBatch(11L);

        assertThat(httpCalls.get()).as("一次点击 = 一次请求（重新打，不是拿旧响应重配）").isEqualTo(1);
        assertThat(resent.getStatus()).isEqualTo("SUCCESS");
        assertThat(inserted).as("新运行里：摘要行 + 那一条消息").hasSize(2);
        assertThat(updated).extracting(MessageStep::getId)
                .as("旧的那一批（摘要行 + 还原那一步那条失败行）都标 REDELIVERED")
                .containsExactlyInAnyOrder(11L, 21L);
    }

    @Test
    void aBatchThatIsNotFailedIsRefusedBeforeAnyRequest() {
        MessageStep summary = step(11L, 7L, "batch:B-1", OUTLET_STEP, "SUCCESS", null, "{\"items\":[]}");
        when(messageStepMapper.selectById(11L)).thenReturn(summary);

        assertThatThrownBy(() -> service().redeliverBatch(11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅整批失败");

        assertThat(httpCalls.get()).as("拒绝的时候一次请求都不发").isZero();
        verify(messageStepMapper, never()).insert(any(MessageStep.class));
    }

    @Test
    void aStepThatIsNotASummaryRowIsRefused() {
        MessageStep ordinary = step(11L, 7L, "msg-1", OUTLET_STEP, "FAILED", 1, "{\"code\":\"PO-1\"}");
        when(messageStepMapper.selectById(11L)).thenReturn(ordinary);

        assertThatThrownBy(() -> service().redeliverBatch(11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("摘要行");

        assertThat(httpCalls.get()).isZero();
    }

    @Test
    void aStepThatIsNotAnOutletIsRefused() {
        // 摘要行落在**合并**那一步（旧口径的形状）不再被认：摘要行归出站
        MessageStep summary = step(11L, 7L, "batch:B-1", 1, "FAILED", null, "{\"items\":[]}");
        when(messageStepMapper.selectById(11L)).thenReturn(summary);

        assertThatThrownBy(() -> service().redeliverBatch(11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是出站动作");

        assertThat(httpCalls.get()).isZero();
    }

    @Test
    void aFailedResendRecordsTheSummaryAndEveryMessageAsFailedAndKeepsTheOldBatch() {
        MessageStep summary = step(11L, 7L, "batch:B-1", OUTLET_STEP, "FAILED", null,
                "{\"items\":[{\"code\":\"PO-1\"},{\"code\":\"PO-2\"}]}");
        MessageStep first = step(12L, 7L, "msg-1", OUTLET_STEP, "FAILED", 1, "{\"code\":\"PO-1\"}");
        MessageStep second = step(13L, 7L, "msg-2", OUTLET_STEP, "FAILED", 2, "{\"code\":\"PO-2\"}");
        when(messageStepMapper.selectById(11L)).thenReturn(summary);
        when(messageStepMapper.selectList(any())).thenReturn(List.of(first, second));
        httpStatus.set(500);
        httpResponse.set("{\"error\":\"target down\"}");

        assertThatThrownBy(() -> service().redeliverBatch(11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重发这一批失败");

        assertThat(httpCalls.get()).isEqualTo(1);
        assertThat(inserted).as("新运行里摘要行 + 这一批每条都记 FAILED（同因）").hasSize(3);
        assertThat(inserted).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo("FAILED");
            assertThat(row.getError()).isNotBlank();
        });
        assertThat(updated).as("失败的一趟不该把旧的那一批标成已重发").isEmpty();
    }

    // ---------- 脚手架 ----------

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setId(index + 100L);
        action.setJobId(JOB_ID);
        action.setActionIndex(index);
        action.setType(kind);
        action.setName(kind.name().toLowerCase() + "-" + index);
        action.setAddition(addition);
        return action;
    }

    private MessageStep step(Long id, Long runId, String messageId, int step, String status,
                             Integer batchSeq, String inputSnapshot) {
        MessageStep row = new MessageStep();
        row.setId(id);
        row.setRunId(runId);
        row.setMessageId(messageId);
        row.setStep(step);
        row.setActionName("http-call-" + step);
        row.setStatus(status);
        row.setBatchId("B-1");
        row.setBatchSeq(batchSeq);
        row.setBusinessKey(batchSeq == null ? null : "PO-" + batchSeq);
        row.setInputSnapshot(inputSnapshot);
        return row;
    }

    private MessageHistoryService service() {
        return new MessageHistoryService(jobRunMapper, messageStepMapper, jobMapper, actionMapper,
                new ObjectMapper(), context, new JobLastRunWriter(jobMapper));
    }

    /** 水位：这一档用不到，给个空的。 */
    private static final class MemoryWatermarkStore implements com.example.edgegateway.WatermarkStore {
        @Override
        public long get(String key) {
            return 0L;
        }

        @Override
        public void put(String key, long value) {
        }
    }
}
