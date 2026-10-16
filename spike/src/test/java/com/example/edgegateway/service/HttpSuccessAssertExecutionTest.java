package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.config.action.HttpCallConfig;
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
import com.example.edgegateway.service.sql.SqlDialects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP_CALL 的**成功条件断言**（#148）走真实 Camel 链的那一档：链是
 * `READ_SQL → FIELD_MAP → HTTP_CALL → WRITE_UPSERT`，源库与写回库都是真实 H2、目标端是本地 HTTP 替身。
 *
 * <p>票面要治的缺口：目标端**永远 200、业务结果写在响应体里**，网关却只看状态码——一条业务上被拒
 * 的数据会被记成 SUCCESS、水位照常推进、下游照常执行，运维无从发现。断言把这一档接进网关既有的
 * 失败面（重试退避 → FAILED 消息步 → 运行 FAILED → 水位不推进 → 重跑任务整批重走）。</p>
 *
 * <p>纯逻辑的判定表在 {@link HttpSuccessAssertTest}；这里考的是**接线**：断言插在记账之前、
 * 失败步只有一行、快照与上下文不被污染、下游不执行。</p>
 */
class HttpSuccessAssertExecutionTest {

    private static final Long DB_DS = 1L;
    private static final Long HTTP_DS = 2L;
    private static final int STEP_READ = 1;
    private static final int STEP_MAP = 2;
    private static final int STEP_CALL = 3;
    private static final int STEP_MAP_RESPONSE = 4;
    private static final int STEP_WRITE = 5;

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

    private final Map<Long, JobRun> runs = new ConcurrentHashMap<>();
    private final Map<Long, MessageStep> steps = new ConcurrentHashMap<>();
    private long runSeq = 1;
    private long stepSeq = 1;

    private final List<Long> watermarkPuts = new ArrayList<>();
    private final Map<String, Long> watermarkValues = new HashMap<>();
    private final MemoryWatermarkStore watermarkStore = new MemoryWatermarkStore();

    private final AtomicInteger httpCalls = new AtomicInteger();
    private final List<String> httpRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger httpStatus = new AtomicInteger(200);
    private final AtomicReference<String> httpBody = new AtomicReference<>("{}");

    private HttpServer target;
    private JdbcTemplate customerJdbc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource customerDb = new JdbcDataSource();
        customerDb.setURL("jdbc:h2:mem:success" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        customerDb.setUser("sa");
        customerJdbc = new JdbcTemplate(customerDb);
        customerJdbc.execute("create table po_order (id bigint primary key, order_no varchar(32))");
        customerJdbc.execute("create table po_receipt (order_no varchar(32) primary key, handle varchar(32))");
        customerJdbc.update("insert into po_order(id, order_no) values (1, 'PO-1')");

        when(dataSourceManager.get(DB_DS)).thenReturn(customerDb);
        when(dataSourceManager.dialect(DB_DS)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        DataSource db = new DataSource();
        db.setId(DB_DS);
        db.setType(DataSourceType.MYSQL);
        db.setAddress("jdbc:h2:mem:x");
        db.setAddition(Map.of("username", "sa", "password", ""));
        when(dataSourceMapper.selectById(DB_DS)).thenReturn(db);

        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            httpCalls.incrementAndGet();
            httpRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String payload = httpBody.get();
            byte[] body = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        target.start();
        DataSource http = new DataSource();
        http.setId(HTTP_DS);
        http.setType(DataSourceType.HTTP);
        http.setAddress("http://127.0.0.1:" + target.getAddress().getPort());
        http.setAddition(Map.of());
        when(dataSourceMapper.selectById(HTTP_DS)).thenReturn(http);

        bindInMemory();
        httpCalls.set(0);
        httpStatus.set(200);
    }

    @AfterEach
    void tearDown() {
        if (target != null) {
            target.stop(0);
        }
    }

    // ---------- 配置期 ----------

    @Test
    void expectWithoutPathIsRejectedAtSaveTime() {
        assertThatThrownBy(() -> HttpCallConfig.from(
                Map.of("dataSourceId", HTTP_DS, "successExpect", "E001")).validate())
                .as("只填期望值不填路径：保存期就拒，别等到运行时才发现不知道看哪里")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successPath");
    }

    @Test
    void pathWithoutExpectIsAccepted() {
        HttpCallConfig.from(Map.of("dataSourceId", HTTP_DS, "successPath", "handle")).validate();
    }

    @Test
    void blankSuccessFieldsStayOutOfTheSavedShape() {
        Map<String, Object> saved = HttpCallConfig.from(
                Map.of("dataSourceId", HTTP_DS, "path", "/register", "successPath", "", "successExpect", ""))
                .toMap();

        assertThat(saved).as("前端把两格清空后保存：addition 里不该出现它们")
                .doesNotContainKeys("successPath", "successExpect");
    }

    // ---------- 票面核心用例 ----------

    /**
     * 票面第一档：**200 + `{"code":"E001","msg":"编号重复"}`**，配置 `successPath=code`、`successExpect=0`
     * （目标端说「编号重复」，而这条链只认 `0`）。
     *
     * <p>该步 FAILED、只有一行失败行（没有 SUCCESS 行）、运行 FAILED、水位不推进、下游写回不执行，
     * 且 `error` 里能看到响应原文片段。</p>
     */
    @Test
    void businessErrorInABodyWithStatus200FailsTheStep() throws Exception {
        httpBody.set("{\"code\":\"E001\",\"msg\":\"编号重复\"}");
        Job job = job(1L);
        List<Action> actions = chain("code", "0");

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        JobRun run = runNumber(1);
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getError())
                .as("错误信息要能直接贴给对接方：判定依据 + 响应原文片段")
                .contains("目标端业务失败")
                .contains("响应 code=E001")
                .contains("成功条件 code=0 不满足")
                .contains("编号重复");
        assertThat(stepsAt(run.getId(), STEP_CALL))
                .as("该步只有一行 FAILED——先写一行 SUCCESS 再记一行 FAILED 会留下半截记录")
                .hasSize(1)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("FAILED"));
        assertThat(stepsAt(run.getId(), STEP_CALL).getFirst().getAttempt())
                .as("没配重试：只尝试一次（attempt 口径不变）")
                .isEqualTo(1);
        assertThat(stepsAt(run.getId(), STEP_CALL).getFirst().getError())
                .as("失败步的 error 里能看到响应原文片段；data_snapshot 只在成功那一路记（既有口径不变）")
                .contains("编号重复");
        assertThat(stepsAt(run.getId(), STEP_CALL).getFirst().getDataSnapshot())
                .as("断言失败走失败面：不写快照，也不写 context")
                .isNull();
        assertThat(stepsAt(run.getId(), STEP_MAP_RESPONSE)).as("还原/取响应那一步不执行").isEmpty();
        assertThat(stepsAt(run.getId(), STEP_WRITE)).as("下游写回不执行").isEmpty();
        assertThat(watermarkPuts).as("水位不推进 → 重跑任务整批重走").isEmpty();
        assertThat(queryString("select count(*) from po_receipt")).isEqualTo("0");
    }

    @Test
    void satisfyingValueWithStatus200Succeeds() throws Exception {
        httpBody.set("{\"code\":\"0\",\"results\":[{\"handle\":\"H1\"}]}");
        Job job = job(2L);
        List<Action> actions = chain("code", "0");

        run(job, actions);

        JobRun run = runNumber(1);
        assertThat(run.getStatus()).as("条件满足：链路照常往下 | " + describe(run)).isEqualTo("SUCCESS");
        assertThat(stepsAt(run.getId(), STEP_CALL)).hasSize(1)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        assertThat(queryString("select handle from po_receipt where order_no = 'PO-1'"))
                .as("下游照常执行（响应里的 handle 取得到）").isEqualTo("H1");
        assertThat(watermarkPuts).as("整批成功才推进水位").containsExactly(1L);
    }

    // ---------- 只判存在 ----------

    @Test
    void pathWithoutExpectOnlyChecksExistence() throws Exception {
        httpBody.set("{\"handle\":\"H1\"}");
        Job job = job(3L);

        run(job, chain("handle", null));

        assertThat(runNumber(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void missingFieldFailsWhenOnlyExistenceIsRequired() throws Exception {
        httpBody.set("{\"code\":\"0\"}");
        Job job = job(4L);

        assertThatThrownBy(() -> run(job, chain("handle", null))).isInstanceOf(Exception.class);

        JobRun run = runNumber(1);
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getError()).contains("handle=null").contains("有值");
        assertThat(stepsAt(run.getId(), STEP_CALL)).hasSize(1)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("FAILED"));
    }

    // ---------- 空响应体 ----------

    @Test
    void emptyResponseBodyWithSuccessConditionFails() throws Exception {
        httpStatus.set(204);
        httpBody.set(null);
        Job job = job(5L);

        assertThatThrownBy(() -> run(job, chain("code", "0"))).isInstanceOf(Exception.class);

        JobRun run = runNumber(1);
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(run.getError())
                .as("配了成功条件却什么都没收到：协议违例，错误信息得让人一眼分辨")
                .contains("响应体为空");
        assertThat(stepsAt(run.getId(), STEP_CALL)).hasSize(1)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("FAILED"));
    }

    // ---------- 类型归一与嵌套路径 ----------

    @Test
    void jsonNumberZeroSatisfiesTextZero() throws Exception {
        httpBody.set("{\"code\":0}");
        Job job = job(6L);

        run(job, chain("code", "0"));

        assertThat(runNumber(1).getStatus())
                .as("响应里的数字 0 与配置里的文本 \"0\" 是同一件事——严格类型比较会误判成失败")
                .isEqualTo("SUCCESS");
    }

    @Test
    void nestedPathIsUsable() throws Exception {
        httpBody.set("{\"data\":{\"status\":\"OK\"}}");
        Job job = job(7L);

        run(job, chain("data.status", "OK"));

        assertThat(runNumber(1).getStatus()).isEqualTo("SUCCESS");
        assertThat(stepsAt(runNumber(1).getId(), STEP_CALL)).hasSize(1);
    }

    // ---------- 快照口径 ----------

    /**
     * 截断只为可读：失败那一行的 {@code error} 里只放响应前 200 字符——长响应不会被整份塞进错误列。
     */
    @Test
    void failureMessageIsTruncatedForLongResponses() throws Exception {
        String longText = "y".repeat(400);
        httpBody.set("{\"code\":\"E001\",\"msg\":\"" + longText + "\"}");
        Job job = job(8L);

        assertThatThrownBy(() -> run(job, chain("code", "0"))).isInstanceOf(Exception.class);

        JobRun run = runNumber(1);
        assertThat(run.getError())
                .contains("前 200 字符")
                .doesNotContain(longText);
        assertThat(stepsAt(run.getId(), STEP_CALL).getFirst().getError())
                .as("失败步的 error 同样只放片段")
                .contains("前 200 字符")
                .doesNotContain(longText);
    }

    // ---------- 与既有失败面的关系 ----------

    /**
     * 断言失败按普通异常走：{@code retryCount} / {@code backoffMs} 生效，每一次投递都记在 {@code attempt} 上
     * （与 {@code RouteGeneratorAttemptTest} 同口径）。
     *
     * <p>这里只断言"重投真的发生了几次"，<b>不断言目标端收到几次</b>：响应已经是 200、挂在断言上的失败
     * 属于「出站之后」的错，重投复用同一份响应，不会重新发一次请求；只有失败发生在**出站自己**时
     * （4xx/5xx / 连不上）才会重发。这个区别是既有行为，不由本票改变。</p>
     */
    @Test
    void assertionFailureIsRetriedLikeAnyOtherException() throws Exception {
        httpBody.set("{\"code\":\"E001\"}");
        Job job = job(9L);
        job.setRetryCount(2);
        job.setBackoffMs(1L);

        assertThatThrownBy(() -> run(job, chain("code", "0"))).isInstanceOf(Exception.class);

        JobRun run = runNumber(1);
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(stepsAt(run.getId(), STEP_CALL)).hasSize(1)
                .allSatisfy(step -> {
                    assertThat(step.getAttempt()).as("重试 2 次 = 一共尝试 3 次").isEqualTo(3);
                    assertThat(step.getStatus()).isEqualTo("FAILED");
                });
        assertThat(stepsAt(run.getId(), STEP_WRITE)).as("重试耗尽仍未通过，下游不执行").isEmpty();
        assertThat(watermarkPuts).as("水位不推进").isEmpty();
    }

    /**
     * 重跑任务：断言失败这一批水位没推进，修好目标端之后再执行同一个任务，**这一批重新发送**并成功，
     * 水位正常推进（#147 的口径：失败恢复统一为「重跑任务」）。
     */
    @Test
    void rerunningAfterFixingTheTargetPushesTheWatermark() throws Exception {
        httpBody.set("{\"code\":\"E001\"}");
        Job job = job(10L);
        List<Action> actions = chain("code", "0");

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);
        assertThat(runNumber(1).getStatus()).isEqualTo("FAILED");
        assertThat(watermarkPuts).isEmpty();

        httpBody.set("{\"code\":\"0\",\"results\":[{\"handle\":\"H1\"}]}");
        run(job, actions);

        JobRun second = runNumber(2);
        assertThat(second.getStatus()).as("重跑这一趟跑成了 | " + describe(second)).isEqualTo("SUCCESS");
        assertThat(runs).as("重跑是新的一次运行").hasSize(2);
        assertThat(httpCalls.get()).as("这一批重新发送（首跑 1 + 重跑 1）").isEqualTo(2);
        assertThat(watermarkPuts).as("跑完这一批，水位正常推进").containsExactly(1L);
        assertThat(queryString("select handle from po_receipt where order_no = 'PO-1'")).isEqualTo("H1");
    }

    // ---------- 脚手架 ----------

    /**
     * 票面那条链：`READ_SQL → FIELD_MAP(请求体) → HTTP_CALL(带成功条件) → FIELD_MAP(把响应里的 handle 取出来) → WRITE_UPSERT`。
     * 写回的值取自 HTTP 响应（`results[0].handle`），所以「断言没过就没写回」看得出来。
     */
    private List<Action> chain(String successPath, String successExpect) {
        Map<String, Object> call = new java.util.LinkedHashMap<>();
        call.put("dataSourceId", HTTP_DS);
        call.put("path", "/register");
        call.put("method", "POST");
        if (successPath != null) {
            call.put("successPath", successPath);
        }
        if (successExpect != null) {
            call.put("successExpect", successExpect);
        }
        return List.of(
                action(STEP_READ, ActionKind.READ_SQL, Map.of("dataSourceId", DB_DS,
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "businessKey", "order_no")),
                action(STEP_MAP, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", STEP_READ, "source", "order_no", "target", "order_no")))),
                action(STEP_CALL, ActionKind.HTTP_CALL, call),
                action(STEP_MAP_RESPONSE, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", STEP_READ, "source", "order_no", "target", "order_no"),
                        Map.of("sourceStep", STEP_CALL, "source", "results[0].handle", "target", "handle")))),
                action(STEP_WRITE, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setType(kind);
        action.setName(kind.name().toLowerCase() + "_" + index);
        action.setAddition(addition);
        return action;
    }

    private Job job(Long id) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(1);
        return job;
    }

    /** 执行一次任务：每次都是新的一套路由（与「重跑任务」在真机上重建路由一致），水位存共用一份。 */
    private void run(Job job, List<Action> actions) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        RouteGenerator generator = new RouteGenerator(
                dataSourceMapper, watermarkStore, service(context), dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        context.removeRouteDefinition(context.getRouteDefinition("resume-" + job.getId()));
        context.start();
        try {
            context.createProducerTemplate().requestBody("direct:job-" + job.getId(), Map.of());
        } finally {
            context.stop();
        }
    }

    private MessageHistoryService service(DefaultCamelContext context) {
        return new MessageHistoryService(jobRunMapper, messageStepMapper, jobMapper, actionMapper,
                new ObjectMapper(), context, new JobLastRunWriter(jobMapper));
    }

    private void bindInMemory() {
        when(actionMapper.selectList(any())).thenReturn(List.of());
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

    private final class MemoryWatermarkStore implements WatermarkStore {
        @Override
        public long get(String key) {
            return watermarkValues.getOrDefault(key, 0L);
        }

        @Override
        public void put(String key, long value) {
            watermarkPuts.add(value);
            watermarkValues.put(key, value);
        }
    }

    private JobRun runNumber(int n) {
        List<JobRun> ordered = runs.values().stream()
                .sorted(java.util.Comparator.comparing(JobRun::getId)).toList();
        assertThat(ordered).as("第 %d 次执行已经落库", n).hasSizeGreaterThanOrEqualTo(n);
        return ordered.get(n - 1);
    }

    private List<MessageStep> stepsAt(Long runId, int step) {
        return steps.values().stream()
                .filter(s -> runId.equals(s.getRunId()) && s.getStep() == step)
                .sorted(java.util.Comparator.comparing(MessageStep::getId))
                .toList();
    }

    private String queryString(String sql) {
        List<String> rows = customerJdbc.queryForList(sql, String.class);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String describe(JobRun run) {
        return "run=" + run.getStatus() + "/" + run.getError() + " steps=" + steps.values().stream()
                .map(s -> s.getStep() + ":" + s.getStatus())
                .toList();
    }
}
