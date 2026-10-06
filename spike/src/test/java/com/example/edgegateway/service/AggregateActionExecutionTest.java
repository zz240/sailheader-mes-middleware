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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 合并 + 扇回（#142 / ADR-0017 决策 6 的修订版）的链级验收：真跑一条 Camel 路由
 * （H2 当客户库 + 本地 HTTP 替身），断言**库里落下来的消息步行**与**替身收到的请求**。
 *
 * <p>对称设计的形状：`读 → 合并（只打包）→ 出站（HTTP_CALL / MQ_PUBLISH）→ 扇回（FANOUT）→ 写库`。
 * 票面那几条打在这儿：</p>
 * <ul>
 *   <li><b>只发一次</b>：整批打成一条报文，出站动作因此只发一次，请求体是整批；</li>
 *   <li><b>摘要行落在出站那一步</b>：`message_id = batch:<批号>`，整份请求与整份响应只在它身上；</li>
 *   <li><b>扇回沿用原身份</b>：回到原来那几条消息（消息标识不变）、不换批号，每条只存自己那一片；</li>
 *   <li><b>同成同败</b>：出站 5xx → 摘要行 + 每条各一行 FAILED；响应缺一条 → 扇回那一步每条 FAILED；</li>
 *   <li><b>多目标</b>：两个出站动作 = 同一批各发一次；<b>空批</b>：读不到新数据时一次请求都不发。</li>
 * </ul>
 */
class AggregateActionExecutionTest {

    private static final long HTTP_DS = 2L;
    private static final long DB_DS = 1L;
    /** 链首是 MQ_CONSUME 那几条用例用一个 RabbitMQ 数据源（测试里不连 broker，只建路由再摘掉消费路由）。 */
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

    private final List<Long> watermarkPuts = new ArrayList<>();
    private final Map<String, Long> watermarks = new HashMap<>();

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

    @AfterEach
    void tearDown() {
        if (target != null) {
            target.stop(0);
        }
    }

    /**
     * 票面那条端到端：`读 → 合并 → 出站 → 扇回 → 写库`。
     *
     * <p>读库一批 3 行 → 合并打成一条 → 出站**只发一次**（请求体是整批）→ 扇回按 {@code code} 配对、
     * 结果落进各自的 {@code handle} → 写库逐行落 3 行。</p>
     */
    @Test
    void readBatchIsPackedSentOnceAndFannedBackToTheSameMessages() throws Exception {
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"},"
                + "{\"code\":\"PO-3\",\"handle\":\"H3\"}]");
        Job job = job(31L);
        List<Action> actions = List.of(
                readOrders(),
                aggregate(2),
                outlet(3),
                fanout(4, 3, "handle"),
                writeMapping(5, 1, 4, "order_no"),
                action(6, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
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

        // 合并那一步：一行，输入是整批、输出是打包报文（不是摘要行——摘要行在出站那一步）
        List<MessageStep> atPack = stepsAt(run.getId(), 2);
        assertThat(atPack).as("合并只记一行（整批 → 打包报文）").hasSize(1);
        assertThat(atPack.getFirst().getDataSnapshot()).isEqualTo(lastRequest.get());
        assertThat(atPack.getFirst().getMessageId()).as("打包出来那一条有自己的消息标识，不是 batch: 摘要行")
                .doesNotStartWith(MessageHistoryService.SUMMARY_MESSAGE_PREFIX);

        // 出站那一步：唯一的一条摘要行——整份请求与整份响应都在它身上
        List<MessageStep> atOutlet = stepsAt(run.getId(), 3);
        assertThat(atOutlet).as("出站只记那一行整批载体").hasSize(1);
        MessageStep summary = atOutlet.getFirst();
        assertThat(summary.getMessageId()).startsWith(MessageHistoryService.SUMMARY_MESSAGE_PREFIX);
        assertThat(summary.getInputSnapshot()).as("摘要行承载整份请求").isEqualTo(lastRequest.get());
        assertThat(summary.getDataSnapshot()).as("摘要行承载整份响应").isEqualTo(httpResponse.get());
        assertThat(summary.getBatchSeq()).isNull();

        // 扇回那一步：回到**原来那几条**消息，每条只存自己那一片
        List<MessageStep> fanned = stepsAt(run.getId(), 4);
        assertThat(fanned).as("扇回给这一批每条各记一行").hasSize(3);
        assertThat(fanned).extracting(MessageStep::getMessageId)
                .as("沿用原来那几条消息的标识（不新造）")
                .containsExactlyInAnyOrderElementsOf(
                        stepsAt(run.getId(), 1).stream().map(MessageStep::getMessageId).toList());
        assertThat(fanned).allSatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo("SUCCESS");
            assertThat(step.getBatchId()).as("不换批号：与摘要行同号").isEqualTo(summary.getBatchId());
            assertThat(step.getDataSnapshot()).as("只存自己那一片").contains(step.getBusinessKey());
        });
        assertThat(fanned).extracting(MessageStep::getBatchSeq).containsExactlyInAnyOrder(1, 2, 3);

        assertThat(customerJdbc.queryForList("select order_no from po_receipt order by order_no", String.class))
                .as("紧随其后的写库动作逐行落库").containsExactly("PO-1", "PO-2", "PO-3");
        assertThat(customerJdbc.queryForList("select handle from po_receipt order by order_no", String.class))
                .as("配对结果落在 attachAs 那个键上，写库直接用它").containsExactly("H1", "H2", "H3");
    }

    /**
     * 整合批 5xx（票面 AC）：**出站**那一步失败 → 摘要行 + 这一批**每条各一行** FAILED（同因），
     * 不留「看着成功其实没发出去」的行；水位不推进、一行都不写库。
     */
    @Test
    void failedOutletMarksTheSummaryAndEveryMessageFailed() throws Exception {
        httpStatus.set(500);
        httpResponse.set("{\"error\":\"target down\"}");
        Job job = job(32L);
        job.setRetryCount(0);
        List<Action> actions = List.of(
                readOrders(),
                aggregate(2),
                outlet(3),
                fanout(4, 3, "handle"),
                action(5, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        JobRun run = onlyRun();
        assertThat(run.getStatus()).isEqualTo("FAILED");
        assertThat(watermarkPuts).as("这一批没跑成，水位不推进").isEmpty();
        assertThat(customerJdbc.queryForObject("select count(*) from po_receipt", Integer.class))
                .as("整批没发出去，一行都不该写").isZero();

        List<MessageStep> atOutlet = stepsAt(run.getId(), 3);
        assertThat(atOutlet).as("摘要行 + 这一批每条各一行，全部 FAILED").hasSize(4);
        assertThat(atOutlet).allSatisfy(step -> {
            assertThat(step.getStatus()).isEqualTo("FAILED");
            assertThat(step.getError()).as("同因").isNotBlank();
        });
        assertThat(atOutlet).filteredOn(step -> !step.getMessageId().startsWith("batch:"))
                .allSatisfy(step -> assertThat(step.getInputSnapshot()).as("每条带自己那一片输入").isNotBlank());
    }

    /**
     * 响应配不上某一条（票面「每条拿到自己那份」的另一面）：**扇回那一步整批失败**——
     * 每条各记一行 FAILED 并点名缺的业务键；出站那一步仍是成功（请求确实发出去了）。
     */
    @Test
    void responseMissingOneKeyFailsTheWholeBatchAtTheFanoutStep() throws Exception {
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-3\",\"handle\":\"H3\"}]");
        Job job = job(33L);
        List<Action> actions = List.of(
                readOrders(),
                aggregate(2),
                outlet(3),
                fanout(4, 3, "handle"));

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        JobRun run = onlyRun();
        assertThat(stepsAt(run.getId(), 3)).as("出站那一步是成功的（请求发了、响应也回来了）")
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        List<MessageStep> fanned = stepsAt(run.getId(), 4);
        assertThat(fanned).as("扇回给这一批每条各记一行 FAILED").hasSize(3);
        assertThat(fanned).allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("FAILED"));
        assertThat(fanned).anySatisfy(step -> assertThat(step.getError()).contains("PO-2"));
    }

    /**
     * 拆分之后再合并（票面 AC 的形状 `读 → 拆分 → 合并 → 出站 → 扇回 → 写库`）：一条报文摊成 N 条子消息，
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
                outlet(4),
                fanout(5, 4, "handle"),
                writeMapping(6, 2, 5, "code", "order_no"),
                action(7, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("code", "C-1");
        first.put("order_no", "PO-1");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("code", "C-2");
        second.put("order_no", "PO-2");
        Map<String, Object> message = Map.of("codes", List.of(first, second));
        run(job, actions, "direct:job-34", message);

        assertThat(httpCalls.get()).as("两条子消息合成一次请求").isEqualTo(1);
        assertThat(lastRequest.get()).isEqualTo(
                "{\"items\":[{\"code\":\"C-1\",\"order_no\":\"PO-1\"},{\"code\":\"C-2\",\"order_no\":\"PO-2\"}]}");
        JobRun run = onlyRun();
        assertThat(stepsAt(run.getId(), 5)).as("扇回：两条子消息各一行").hasSize(2);
        assertThat(customerJdbc.queryForList("select handle from po_receipt order by order_no", String.class))
                .as("配对到的那一份经映射取出来、逐行写库").containsExactly("H1", "H2");
    }

    /**
     * **多目标**：一次打包、两个出站动作各发一次（同一批）。出口的变化全在链上，合并配置一个字不用改。
     *
     * <p>第二个终端收到的是同一份整批报文（带着第一个目标回填的 {@code handle}——扇回在出站**之后**，
     * 所以第二个出口看到的还是打包出来的那份报文）。</p>
     */
    @Test
    void twoOutletsSendTheSameBatchToOneTargetEach() throws Exception {
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicReference<String> secondRequest = new AtomicReference<>();
        HttpServer second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        second.createContext("/", exchange -> {
            secondCalls.incrementAndGet();
            secondRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("[{\"code\":\"PO-1\",\"receipt\":\"R1\"},{\"code\":\"PO-2\",\"receipt\":\"R2\"},"
                    + "{\"code\":\"PO-3\",\"receipt\":\"R3\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        second.start();
        DataSource secondTarget = new DataSource();
        secondTarget.setId(4L);
        secondTarget.setType(DataSourceType.HTTP);
        secondTarget.setAddress("http://127.0.0.1:" + second.getAddress().getPort());
        secondTarget.setAddition(Map.of());
        when(dataSourceMapper.selectById(4L)).thenReturn(secondTarget);
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"},"
                + "{\"code\":\"PO-3\",\"handle\":\"H3\"}]");

        Job job = job(37L);
        List<Action> actions = List.of(
                readOrders(),
                aggregate(2),
                outlet(3, HTTP_DS, "/register"),
                outlet(4, 4L, "/receipt"),
                fanout(5, 4, "receipt"));
        try {
            run(job, actions);

            assertThat(httpCalls.get()).as("第一个终端只收到一次（整批）").isEqualTo(1);
            assertThat(secondCalls.get()).as("第二个终端也只收到一次（同一批）").isEqualTo(1);
            assertThat(lastRequest.get()).as("第一个报文是 3 条子消息").contains("\"order_no\":\"PO-1\"");
            assertThat(secondRequest.get()).as("第二个报文是同一批 3 条").contains("\"order_no\":\"PO-1\"");

            JobRun run = onlyRun();
            assertThat(run.getStatus()).isEqualTo("SUCCESS");
            assertThat(stepsAt(run.getId(), 3)).as("第一个出口：那一行摘要").hasSize(1);
            assertThat(stepsAt(run.getId(), 4)).as("第二个出口：那一行摘要").hasSize(1);
            assertThat(stepsAt(run.getId(), 4).getFirst().getMessageId())
                    .startsWith(MessageHistoryService.SUMMARY_MESSAGE_PREFIX);
            assertThat(stepsAt(run.getId(), 5)).as("扇回（按第二个出口的响应）：3 条").hasSize(3);
        } finally {
            second.stop(0);
        }
    }

    /**
     * **空批不发空报文**：读库没读到新行时，这一批是空的——出站动作一次都不能被调用
     * （否则每个「没有新数据」的运行都会朝目标端打一下）。
     */
    @Test
    void anEmptyBatchSendsNothing() throws Exception {
        customerJdbc.execute("delete from po_order");
        Job job = job(38L);
        List<Action> actions = List.of(
                readOrders(),
                outlet(2),
                fanout(3, 2, "handle"));

        run(job, actions);

        assertThat(httpCalls.get()).as("空批不发（一次请求都没有）").isZero();
        JobRun run = onlyRun();
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
        assertThat(steps).as("空批一行消息步都没有").isEmpty();
    }

    /**
     * 没有上游读取批时（重放 / 单条重投走到合并点）它**自己就是一批**：当场打包发出去、不等
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
                outlet(3),
                fanout(4, 3, "handle"),
                writeMapping(5, 1, 4, "code", "order_no"),
                action(6, ActionKind.WRITE_UPSERT, Map.of("dataSourceId", DB_DS,
                        "tableName", "po_receipt", "idempotencyKey", "order_no")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("code", "PO-9");
        input.put("order_no", "PO-9");
        run(job, actions, "direct:job-35", input);

        assertThat(httpCalls.get()).as("只有一条也照样发出去").isEqualTo(1);
        assertThat(lastRequest.get()).isEqualTo("{\"items\":[{\"code\":\"PO-9\",\"order_no\":\"PO-9\"}]}");
        JobRun run = onlyRun();
        assertThat(stepsAt(run.getId(), 3)).as("出站：那一行摘要").hasSize(1);
        assertThat(stepsAt(run.getId(), 4)).as("扇回：那一条消息").hasSize(1);
        assertThat(customerJdbc.queryForObject("select handle from po_receipt where order_no = 'PO-9'", String.class))
                .isEqualTo("H9");
    }

    // ---------- 脚手架 ----------

    /** 读库那一步：3 行、一行一条消息、批大小 10（一批读完）。 */
    private Action readOrders() {
        return action(1, ActionKind.READ_SQL, Map.of("dataSourceId", DB_DS, "businessKey", "order_no",
                "sql", "select id, order_no from po_order where {watermark}",
                "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", 10));
    }

    /** 合并：一格配置——整批包成这个字段。 */
    private Action aggregate(int index) {
        return action(index, ActionKind.AGGREGATE, Map.of("bodyField", "items"));
    }

    /** 出站：链上现成的 HTTP_CALL（出口不再是合并动作的事）。 */
    private Action outlet(int index) {
        return outlet(index, HTTP_DS, "/register");
    }

    private Action outlet(int index, long dataSourceId, String path) {
        return action(index, ActionKind.HTTP_CALL, Map.of("dataSourceId", dataSourceId, "path", path,
                "method", "POST"));
    }

    /** 扇回：从出站那一步的输出里取响应，按配对字段摊回原来那几条消息。 */
    private Action fanout(int index, int sourceStep, String attachAs) {
        return action(index, ActionKind.FANOUT, Map.of("sourceStep", sourceStep,
                "resultKeyField", "code", "attachAs", attachAs));
    }

    /**
     * 扇回之后「紧随其后的写库动作直接用」：先从上游那一步的输出里把要写库的字段挑出来，再从扇回那一步
     * 的输出里取配对到的那一份（{@code sourceStep} 指扇回动作序号、{@code source} 指响应项里那个字段）。
     */
    private Action writeMapping(int index, int bodyStep, int fanoutStep, String... bodyFields) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String field : bodyFields) {
            rules.add(Map.of("sourceStep", bodyStep, "source", field, "target", field));
        }
        rules.add(Map.of("sourceStep", fanoutStep, "source", "handle", "target", "handle"));
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

    private void run(Job job, List<Action> actions) throws Exception {
        run(job, actions, "direct:job-" + job.getId(), Map.of());
    }

    private void run(Job job, List<Action> actions, String uri, Object body) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        RouteGenerator generator = new RouteGenerator(
                dataSourceMapper, new MemoryWatermarkStore(), service(context), dataSourceManager,
                new ReadSqlBatcher());
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

    private List<MessageStep> stepsAt(Long runId, int step) {
        return steps.values().stream()
                .filter(s -> runId.equals(s.getRunId()) && s.getStep() == step)
                .sorted(Comparator.comparing(MessageStep::getId))
                .toList();
    }
}
