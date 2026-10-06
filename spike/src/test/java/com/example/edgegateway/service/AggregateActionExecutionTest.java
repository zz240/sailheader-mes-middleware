package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.example.edgegateway.service.sql.SqlDialects;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 合并动作（#142）的链级验收：真跑一条 Camel 路由（H2 当客户库 + 本地 HTTP 替身），断言**库里落下来的
 * 消息步行**与**替身收到的请求**。
 *
 * <p>票面那几条都打在这儿：</p>
 * <ul>
 *   <li>读库一批 → 合并 → 写库：**只发一次请求**、请求体是整批；每条消息各记一行、只存自己那一片；
 *       摘要行只有一条且整份请求与整份响应都在它身上；</li>
 *   <li>拆分之后再合并（1 → N → 1）：判齐按「这一行摊出几条」算，不是按读了几行算；</li>
 *   <li>整批 5xx：每条相关消息都记 FAILED（同因），不留「看着成功其实没发出去」的行；水位不推进；</li>
 *   <li>响应配不上某条消息：整批失败（同成同败），不静默只回一部分；</li>
 *   <li>没有读库那一步（重放 / 单条重投）时它自己就是一批：**当场发**，不等。</li>
 * </ul>
 */
class AggregateActionExecutionTest {

    private static final long HTTP_DS = 2L;
    private static final long DB_DS = 1L;
    /** 链首是 MQ_CONSUME 那两条用例用一个 RabbitMQ 数据源（测试里不连 broker，只建路由再摘掉消费路由）。 */
    private static final long MQ_DS = 3L;

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

    /** 水位：记下每批提交的值（断言「批失败不推进」）。 */
    private final List<Long> watermarkPuts = new ArrayList<>();
    private final Map<String, Long> watermarks = new HashMap<>();

    /** HTTP 替身：请求次数、最后一次收到的报文、以及它要回的报文/状态码。 */
    private final AtomicInteger httpCalls = new AtomicInteger();
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> httpResponse = new AtomicReference<>("[]");
    private final AtomicInteger httpStatus = new AtomicInteger(200);

    private HttpServer target;
    private JdbcDataSource customerDb;
    private JdbcTemplate customerJdbc;

    @BeforeEach
    void setUp() throws Exception {
        customerDb = new JdbcDataSource();
        customerDb.setURL("jdbc:h2:mem:aggregate" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        customerDb.setUser("sa");
        customerJdbc = new JdbcTemplate(customerDb);
        customerJdbc.execute("create table po_order (id bigint primary key, order_no varchar(32))");
        customerJdbc.execute("create table po_receipt (id bigint, order_no varchar(32) primary key,"
                + " code varchar(32), handle varchar(32))");
        for (int i = 1; i <= 3; i++) {
            customerJdbc.update("insert into po_order(id, order_no) values (?, ?)", i, "PO-" + i);
        }

        when(dataSourceManager.get(DB_DS)).thenReturn(customerDb);
        when(dataSourceManager.dialect(DB_DS)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        DataSource db = new DataSource();
        db.setId(DB_DS);
        db.setType(DataSourceType.MYSQL);
        db.setAddress("jdbc:h2:mem:x");
        db.setAddition(Map.of("username", "sa", "password", ""));
        when(dataSourceMapper.selectById(DB_DS)).thenReturn(db);
        DataSource mq = new DataSource();
        mq.setId(MQ_DS);
        mq.setType(DataSourceType.RABBITMQ);
        mq.setName("mes.exchange");
        mq.setAddition(Map.of("host", "localhost", "port", 5672));
        when(dataSourceMapper.selectById(MQ_DS)).thenReturn(mq);

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
        http.setId(HTTP_DS);
        http.setType(DataSourceType.HTTP);
        http.setAddress("http://127.0.0.1:" + target.getAddress().getPort());
        http.setAddition(Map.of());
        when(dataSourceMapper.selectById(HTTP_DS)).thenReturn(http);

        bindInMemory();
        httpCalls.set(0);
        lastRequest.set(null);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (target != null) {
            target.stop(0);
        }
    }

    /**
     * 票面那条端到端：`读 → 合并 → 写库`。
     *
     * <p>读库一批 3 行 → 合并**只发一次**（请求体是整批）→ 响应按 {@code code} 配对、结果落进各自的
     * {@code handle} → 写库逐行落 3 行。</p>
     */
    @Test
    void readBatchIsMergedIntoOneRequestAndEveryMessageKeepsItsOwnSlice() throws Exception {
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"},"
                + "{\"code\":\"PO-3\",\"handle\":\"H3\"}]");
        Job job = job(31L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", DB_DS, "businessKey", "order_no",
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", 10)),
                aggregate(2),
                writeMapping(3, 1, 2, "order_no"),
                action(4, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));

        run(job, actions);

        assertThat(httpCalls.get()).as("整批只发一次请求（票面第一条硬指标）").isEqualTo(1);
        assertThat(lastRequest.get())
                .as("请求体是整批：bodyField 那一格装 3 条")
                .isEqualTo("{\"items\":[{\"id\":1,\"order_no\":\"PO-1\"},{\"id\":2,\"order_no\":\"PO-2\"},"
                        + "{\"id\":3,\"order_no\":\"PO-3\"}]}");

        JobRun run = onlyRun();
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
        assertThat(watermarkPuts).as("一批跑完推一次水位").containsExactly(3L);

        List<MessageStep> atMerge = stepsAt(run.getId(), 2);
        assertThat(atMerge).as("摘要行 + 3 条消息各自一行").hasSize(4);
        MessageStep summary = summaryOf(atMerge);
        assertThat(summary.getDataSnapshot()).as("摘要行承载整份响应").isEqualTo(httpResponse.get());
        assertThat(summary.getInputSnapshot()).as("摘要行承载整份请求").isEqualTo(lastRequest.get());
        assertThat(summary.getBatchId()).as("摘要行与这一批同号")
                .isEqualTo(atMerge.stream().filter(s -> !s.getMessageId().startsWith("batch:")).findFirst()
                        .orElseThrow().getBatchId());
        assertThat(summary.getBatchSeq()).isNull();
        assertThat(atMerge).allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));

        for (int i = 1; i <= 3; i++) {
            MessageStep own = messageStepByBusinessKey(run.getId(), "PO-" + i, 2);
            assertThat(own.getDataSnapshot()).as("每条只存自己那一片").isEqualTo("{\"code\":\"PO-" + i + "\",\"handle\":\"H" + i + "\"}");
            assertThat(own.getBatchSeq()).as("攒批顺序从 1 起").isEqualTo(i);
        }
        assertThat(customerJdbc.queryForList("select order_no from po_receipt order by order_no", String.class))
                .as("紧随其后的写库动作逐行落库").containsExactly("PO-1", "PO-2", "PO-3");
        assertThat(customerJdbc.queryForList("select handle from po_receipt order by order_no", String.class))
                .as("配对结果落在 attachAs 那个键上，写库直接用它").containsExactly("H1", "H2", "H3");
    }

    /**
     * 整合批 5xx（票面 AC）：每条相关消息都记 FAILED、各自带自己的那一片，不留「看着成功其实没发出去」
     * 的行；水位**不推进**（这一批没跑成）。
     */
    @Test
    void failedBatchMarksEveryMessageFailedAndKeepsTheWatermark() throws Exception {
        httpStatus.set(500);
        httpResponse.set("{\"error\":\"target down\"}");
        Job job = job(32L);
        job.setRetryCount(0);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", DB_DS, "businessKey", "order_no",
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", 10)),
                aggregate(2),
                action(3, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        JobRun run = onlyRun();
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(watermarkPuts).as("这一批没跑成，水位不推进").isEmpty();
        assertThat(customerJdbc.queryForObject("select count(*) from po_receipt", Integer.class))
                .as("整批没发出去，一行都不该写").isZero();

        List<MessageStep> atMerge = stepsAt(run.getId(), 2);
        assertThat(atMerge).as("摘要行 + 3 条消息各自一行，全部 FAILED").hasSize(4);
        assertThat(atMerge).allSatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo("FAILED");
            assertThat(step.getError()).as("同因").isNotBlank();
        });
        assertThat(atMerge).filteredOn(step -> !step.getMessageId().startsWith("batch:"))
                .allSatisfy(step -> assertThat(step.getInputSnapshot()).as("每条带自己那一片输入").isNotBlank());
    }

    /**
     * 响应配不上某一条（票面「每条拿到自己那份」的另一面）：**整批失败**，不静默只回一部分——
     * 配对字段取不到的那条会让整批 FAILED，错误里点名是哪个业务键。
     */
    @Test
    void responseMissingOneKeyFailsTheWholeBatch() throws Exception {
        // 少了 PO-2 那一条
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-3\",\"handle\":\"H3\"}]");
        Job job = job(33L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", DB_DS, "businessKey", "order_no",
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", 10)),
                aggregate(2));

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        JobRun run = onlyRun();
        List<MessageStep> atMerge = stepsAt(run.getId(), 2);
        assertThat(atMerge).hasSize(4);
        assertThat(atMerge).allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("FAILED"));
        assertThat(atMerge).anySatisfy(step -> assertThat(step.getError()).contains("PO-2"));
    }

    /**
     * 拆分之后再合并（票面 AC 的形状 `读 → 拆分 → 合并 → 写库`）：一条报文摊成 N 条子消息，
     * 这 N 条**合成一次请求**。判齐按「这一行摊出几条」算（不是按读了几行算）。
     */
    @Test
    void splitThenAggregateMergesEveryChildIntoOneRequest() throws Exception {
        httpResponse.set("[{\"code\":\"C-1\",\"handle\":\"H1\"},{\"code\":\"C-2\",\"handle\":\"H2\"}]");
        Job job = job(34L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", MQ_DS, "queue", "bom.to.edge",
                        "businessKey", "code")),
                action(2, ActionKind.SPLIT, Map.of("elementKey", "code", "sourcePath", "codes", "keepParent", false)),
                aggregate(3),
                writeMapping(4, 2, 3, "code", "order_no"),
                action(5, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));

        Map<String, Object> first = new java.util.LinkedHashMap<>();
        first.put("code", "C-1");
        first.put("order_no", "PO-1");
        Map<String, Object> second = new java.util.LinkedHashMap<>();
        second.put("code", "C-2");
        second.put("order_no", "PO-2");
        Map<String, Object> message = Map.of("codes", List.of(first, second));
        run(job, actions, "direct:job-34", message);

        assertThat(httpCalls.get()).as("两条子消息合成一次请求").isEqualTo(1);
        assertThat(lastRequest.get()).isEqualTo(
                "{\"items\":[{\"code\":\"C-1\",\"order_no\":\"PO-1\"},{\"code\":\"C-2\",\"order_no\":\"PO-2\"}]}");
        JobRun run = onlyRun();
        assertThat(stepsAt(run.getId(), 3)).as("摘要行 + 2 条子消息").hasSize(3);
        assertThat(customerJdbc.queryForList("select handle from po_receipt order by order_no", String.class))
                .as("配对到的那一份经映射取出来、逐行写库").containsExactly("H1", "H2");
    }

    /**
     * 没有上游读取批时（重放 / 单条重投走到合并点）它**自己就是一批**：当场发、不等
     * （票面「单独重投一条走合并点的数据时，它自己那一批立刻发出去」）。
     */
    @Test
    void aSingleMessageWithoutAnUpstreamBatchIsSentImmediately() throws Exception {
        httpResponse.set("[{\"code\":\"PO-9\",\"handle\":\"H9\"}]");
        Job job = job(35L);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", MQ_DS, "queue", "mes.to.edge",
                        "businessKey", "code")),
                aggregate(2),
                writeMapping(3, 1, 2, "code", "order_no"),
                action(4, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));

        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("code", "PO-9");
        input.put("order_no", "PO-9");
        run(job, actions, "direct:job-35", input);

        assertThat(httpCalls.get()).as("只有一条也照样发出去").isEqualTo(1);
        assertThat(lastRequest.get()).isEqualTo("{\"items\":[{\"code\":\"PO-9\",\"order_no\":\"PO-9\"}]}");
        JobRun run = onlyRun();
        assertThat(stepsAt(run.getId(), 2)).as("摘要行 + 那一条消息").hasSize(2);
        assertThat(customerJdbc.queryForObject("select handle from po_receipt where order_no = 'PO-9'", String.class))
                .isEqualTo("H9");
    }

    // ---------- 脚手架 ----------

    private Action aggregate(int index) {
        return action(index, ActionKind.AGGREGATE, Map.of(
                "dataSourceId", HTTP_DS, "path", "/register", "method", "POST",
                "bodyField", "items", "resultKeyField", "code", "attachAs", "handle"));
    }

    /**
     * 合并之后「紧随其后的写库动作直接用」：先从上游那一步的输出里把要写库的字段挑出来，再从合并那一步
     * 的输出里取配对到的那一份（{@code sourceStep} 指合并动作序号、{@code source} 指响应项里那个字段）。
     */
    private Action writeMapping(int index, int bodyStep, int aggregateIndex, String... bodyFields) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String field : bodyFields) {
            rules.add(Map.of("sourceStep", bodyStep, "source", field, "target", field));
        }
        rules.add(Map.of("sourceStep", aggregateIndex, "source", "handle", "target", "handle"));
        return action(index, ActionKind.FIELD_MAP, Map.of("rules", rules));
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setType(kind);
        action.setName(kind.name().toLowerCase() + "-" + index);
        action.setAddition(addition);
        return action;
    }

    private Job job(Long id) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(1);
        return job;
    }

    /** 建路由 + 起上下文 + 投一次。链首是 MQ_CONSUME 时走 direct 重放路由（测试里不连 broker）。 */
    private void run(Job job, List<Action> actions) throws Exception {
        run(job, actions, "direct:job-" + job.getId(), Map.of());
    }

    private void run(Job job, List<Action> actions, String uri, Object body) throws Exception {
        RouteGenerator generator = new RouteGenerator(
                dataSourceMapper, new MemoryWatermarkStore(), service(), dataSourceManager, new ReadSqlBatcher());
        DefaultCamelContext context = new DefaultCamelContext();
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        if (actions.getFirst().getType() == ActionKind.MQ_CONSUME) {
            context.removeRouteDefinition(context.getRouteDefinition("job-" + job.getId()));
        }
        context.removeRouteDefinition(context.getRouteDefinition("resume-" + job.getId()));
        context.start();
        try {
            context.createProducerTemplate().requestBody(uri, body);
        } finally {
            context.stop();
        }
    }

    private MessageHistoryService service() {
        return new MessageHistoryService(jobRunMapper, messageStepMapper, jobMapper, actionMapper,
                new ObjectMapper(), new org.apache.camel.impl.DefaultCamelContext(), new JobLastRunWriter(jobMapper));
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

    /** 水位：内存里放着，提交过的值记下来（批失败不推进那条靠它断言）。 */
    private final class MemoryWatermarkStore implements WatermarkStore {
        @Override
        public long get(String key) {
            return watermarks.getOrDefault(key, 0L);
        }

        @Override
        public void put(String key, long value) {
            watermarkPuts.add(value);
            watermarks.put(key, value);
        }
    }

    private JobRun onlyRun() {
        assertThat(runs).as("只该有一次运行").hasSize(1);
        return runs.values().iterator().next();
    }

    /** 这一步上所有落下来的行（按 id 排，摘要行不一定在最后）。 */
    private List<MessageStep> stepsAt(Long runId, int step) {
        return steps.values().stream()
                .filter(s -> runId.equals(s.getRunId()) && s.getStep() == step)
                .sorted(Comparator.comparing(MessageStep::getId))
                .toList();
    }

    private MessageStep summaryOf(List<MessageStep> atMerge) {
        return atMerge.stream()
                .filter(step -> step.getMessageId().startsWith(MessageHistoryService.SUMMARY_MESSAGE_PREFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有摘要行: " + atMerge));
    }

    private MessageStep messageStepByBusinessKey(Long runId, String businessKey, int step) {
        return stepsAt(runId, step).stream()
                .filter(step1 -> businessKey.equals(step1.getBusinessKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("步骤 " + step + " 上没有业务键 " + businessKey + " 的行"));
    }
}
