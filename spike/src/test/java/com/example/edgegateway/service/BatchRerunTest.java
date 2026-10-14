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
 * 失败恢复统一为「**重跑任务**」（#147）：整批重发那条路径与摘要行那层记账都删掉之后，
 * 「这一批该不该重发」不再是一个要用户判断的问题——**水位没推进就是唯一判据**。
 *
 * <p>票面那条核心闭环，链是 B6 形状的 7 步（`READ_SQL → FIELD_MAP → AGGREGATE → HTTP_CALL →
 * ONE_TO_MANY(还原) → FIELD_MAP → WRITE_UPSERT`），真跑 Camel 路由（H2 当客户库 + 本地 HTTP 替身）：</p>
 * <ol>
 *   <li>制造「**出站成功、下游写不回去**」：替身第一趟只回两条（缺 `PO-3`）→ 出站那一步 SUCCESS
 *       （请求确实发了），**还原那一步**这一批每条各一行 FAILED（同成同败），水位不推进、一行不写库；</li>
 *   <li>**再执行一次同一个任务**（= 重跑任务）：水位还停在上一批末尾，于是它重新读这一批、重新打包、
 *       重新发出站请求——替身**只多收到一次**该批请求，`WRITE_UPSERT` 补跑成功、回写值正确，
 *       水位推进到该批之后，而原来那次运行仍是 FAILED。</li>
 * </ol>
 *
 * <p>记账口径（#147 删掉摘要行之后）：出站与还原都**只按消息粒度各记一行**，出站那一行记的是合并
 * 打出来那条**信封消息自己**的标识；整条链上不再有 `message_id LIKE 'batch:%'` 的行。</p>
 */
class BatchRerunTest {

    private static final long JOB_ID = 61L;
    private static final long HTTP_DS = 2L;
    private static final long DB_DS = 1L;

    /** B6 那条链的 7 步（序号即动作序号）。 */
    private static final int STEP_READ = 1;
    private static final int STEP_MAP_BEFORE = 2;
    private static final int STEP_AGGREGATE = 3;
    private static final int STEP_OUTLET = 4;
    private static final int STEP_RESTORE = 5;
    private static final int STEP_MAP_AFTER = 6;
    private static final int STEP_WRITE = 7;

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

    /**
     * 水位跨两次执行**共用一份**——这正是本票要考的那条：第一次失败后水位没推进，
     * 所以第二次执行重新读到的是同一批。
     */
    private final List<Long> watermarkPuts = new ArrayList<>();
    private final Map<String, Long> watermarkValues = new HashMap<>();
    private final MemoryWatermarkStore watermarkStore = new MemoryWatermarkStore();

    private final AtomicInteger httpCalls = new AtomicInteger();
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> httpResponse = new AtomicReference<>("[]");
    /**
     * 替身**逐次**收到的报文（与 {@link #httpCalls} 同源）。两批那条用例靠"清空后重跑只剩一次"
     * 来分开「第 1 批有没有被重读」与「第 2 批多收了几次」——只看累计总数会把这两种错法抵消掉。
     */
    private final List<String> httpRequests = new ArrayList<>();

    private HttpServer target;
    private JdbcTemplate customerJdbc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource customerDb = new JdbcDataSource();
        customerDb.setURL("jdbc:h2:mem:rerun" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        customerDb.setUser("sa");
        customerJdbc = new JdbcTemplate(customerDb);
        customerJdbc.execute("create table po_order (id bigint primary key, order_no varchar(32))");
        customerJdbc.execute("create table po_receipt (order_no varchar(32) primary key, handle varchar(32))");
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

        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            httpCalls.incrementAndGet();
            httpRequests.add(body);
            lastRequest.set(body);
            byte[] response = httpResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
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
        httpRequests.clear();
        lastRequest.set(null);
    }

    @AfterEach
    void tearDown() {
        if (target != null) {
            target.stop(0);
        }
    }

    /**
     * 票面那条核心闭环：出站成功、下游写不回去 → **重跑任务**补齐下游。
     *
     * <p>注意重跑的代价：目标端会**再收到一次**这一批请求（至少一次 + 目标端按业务键幂等，
     * 见 `docs/features/reliability`）。这正是删掉「只补下游、不重发出站」那条路径换来的东西。</p>
     */
    @Test
    void rerunningTheJobRefillsTheDownstreamAfterAWriteBackFailure() throws Exception {
        // 第一趟：替身少回一条（缺 PO-3）→ 出站成功、还原那一步配对不上
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"}]");
        Job job = job(JOB_ID);
        List<Action> actions = b6Chain();

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        JobRun first = runNumber(1);
        assertThat(first.getStatus()).as("这一批没跑成，这次运行是 FAILED").isEqualTo("FAILED");
        assertThat(watermarkPuts).as("水位不推进：整批成功才提交").isEmpty();
        assertThat(customerJdbc.queryForObject("select count(*) from po_receipt", Integer.class))
                .as("下游一行都没写回去").isZero();

        assertThat(stepsAt(first.getId(), STEP_OUTLET)).as("出站那一步是成功的：请求确实发出去了")
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));

        List<MessageStep> failedAtRestore = stepsAt(first.getId(), STEP_RESTORE);
        String envelopeId = stepsAt(first.getId(), STEP_AGGREGATE).getFirst().getMessageId();
        assertThat(failedAtRestore).as("还原那一步：这一批每条各一行 + 信封消息自己那一行")
                .hasSize(4);
        assertThat(failedAtRestore).allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("FAILED"));
        assertThat(failedAtRestore).filteredOn(step -> !step.getMessageId().equals(envelopeId))
                .as("该批每条在还原那一步都是 FAILED").hasSize(3)
                .allSatisfy(step -> assertThat(step.getBusinessKey()).startsWith("PO-"));
        assertThat(httpCalls.get()).as("第一趟发了一次").isEqualTo(1);

        // ---- 重跑任务：同一个任务、同一条链，水位还停在原地 ----
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"},"
                + "{\"code\":\"PO-3\",\"handle\":\"H3\"}]");
        run(job, actions);

        JobRun second = runNumber(2);
        assertThat(second.getStatus()).as("重跑这一趟跑成了").isEqualTo("SUCCESS");
        assertThat(runs).as("重跑是一次**新的运行**").hasSize(2);
        assertThat(first.getStatus()).as("原来那次运行仍是 FAILED（重跑不改写历史）").isEqualTo("FAILED");
        assertThat(httpCalls.get()).as("替身一共只多收到一次该批请求（首次 1 + 重跑 1）").isEqualTo(2);
        assertThat(lastRequest.get())
                .as("重跑发出去的是重新打包出来的同一批")
                .isEqualTo("{\"items\":[{\"order_no\":\"PO-1\",\"id\":1},{\"order_no\":\"PO-2\",\"id\":2},"
                        + "{\"order_no\":\"PO-3\",\"id\":3}]}");
        assertThat(watermarkPuts).as("重跑把这一批跑完，水位推进到该批之后").containsExactly(3L);
        assertThat(customerJdbc.queryForList("select order_no from po_receipt order by order_no", String.class))
                .as("WRITE_UPSERT 补跑成功").containsExactly("PO-1", "PO-2", "PO-3");
        assertThat(customerJdbc.queryForList("select handle from po_receipt order by order_no", String.class))
                .as("回写值正确（来自重跑那一次的响应）").containsExactly("H1", "H2", "H3");

        // 记账口径（#147）：出站与还原都只按消息粒度各记一行，不再有摘要行
        assertThat(stepsAt(second.getId(), STEP_OUTLET)).as("出站那一步：只有信封消息自己那一行").hasSize(1);
        assertThat(stepsAt(second.getId(), STEP_RESTORE)).as("还原那一步：这一批每条各一行").hasSize(3);
        assertThat(steps.values()).as("#147：整条链上不再有 batch: 前缀的摘要行")
                .allSatisfy(step -> assertThat(step.getMessageId()).doesNotStartWith("batch:"));
    }

    /**
     * 票面核心用例的**字面场景**：失败落在**写回那一步自己**（#143 的「`FHandle` 不写回」那一档），
     * 出站与还原都成功。
     *
     * <p>与 {@link #rerunningTheJobRefillsTheDownstreamAfterAWriteBackFailure()} 的区别就在这一点上：
     * 那一条是「失败在**还原**（响应里的配对没配上，下游根本没跑到）」，这一条是「失败在**写回**
     * （还原已经成功、报文也配齐了，坏掉的是写库那一步自己）」。两者对「水位不推进 → 重跑整批重走」
     * 的结论一样，都靠重跑补齐下游。</p>
     *
     * <p>注入方式最省事：跑之前把目标表的那一列去掉（**链与报文一个字不改，只让目标库坏掉**），
     * 第一趟跑完再修好——于是写回那一步**真的执行过并失败**，而不是像上一条那样根本没跑到。</p>
     */
    @Test
    void rerunningTheJobRefillsAWriteBackStepThatItselfFailed() throws Exception {
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"},"
                + "{\"code\":\"PO-3\",\"handle\":\"H3\"}]");
        Job job = job(JOB_ID);
        List<Action> actions = b6Chain();
        // 目标表缺一列（handle）：写回那一步自己失败，出站与还原照常成功
        customerJdbc.execute("alter table po_receipt drop column handle");

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        JobRun first = runNumber(1);
        assertThat(first.getStatus()).isEqualTo("FAILED");
        assertThat(watermarkPuts).as("写回失败，水位不推进").isEmpty();
        assertThat(stepsAt(first.getId(), STEP_OUTLET)).as("出站那一步成功").hasSize(1)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        assertThat(stepsAt(first.getId(), STEP_RESTORE))
                .as("还原那一步也成功——这一档与「配对没配上」那条的关键区别").hasSize(3)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        assertThat(stepsAt(first.getId(), STEP_WRITE)).as("写回那一步真的执行过并失败").isNotEmpty()
                .allSatisfy(step -> {
                    assertThat(step.getStatus()).isEqualTo("FAILED");
                    assertThat(step.getError()).isNotBlank();
                });
        assertThat(customerJdbc.queryForObject("select count(*) from po_receipt", Integer.class))
                .as("库里一行都没有").isZero();
        assertThat(httpCalls.get()).as("第一趟发了一次").isEqualTo(1);

        // ---- 修好目标库，重跑同一个任务 ----
        customerJdbc.execute("alter table po_receipt add column handle varchar(32)");
        run(job, actions);

        JobRun second = runNumber(2);
        assertThat(second.getStatus()).isEqualTo("SUCCESS");
        assertThat(first.getStatus()).as("原来那次运行仍是 FAILED").isEqualTo("FAILED");
        assertThat(httpCalls.get()).as("替身只多收到一次该批请求").isEqualTo(2);
        assertThat(watermarkPuts).as("重跑把这一批跑完，水位推进到该批之后").containsExactly(3L);
        assertThat(stepsAt(second.getId(), STEP_WRITE)).as("WRITE_UPSERT 补跑成功：这一批每条各一行")
                .hasSize(3).allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        assertThat(customerJdbc.queryForList("select handle from po_receipt order by order_no", String.class))
                .as("回写值正确").containsExactly("H1", "H2", "H3");
        assertThat(steps.values()).as("#147：整条链上不再有 batch: 前缀的摘要行")
                .allSatisfy(step -> assertThat(step.getMessageId()).doesNotStartWith("batch:"));
    }

    /**
     * 票面那条 AC 里最难钉的一格：**水位语义不变——重跑从未完成的那一批继续，不重读已成功的批**。
     *
     * <p>上面两条用例的夹具里 {@code batchSize} 是 10、源表只有 3 行，所以整条链只有**一批**，
     * 「不重读已成功的批」这条**没有落点**。这一条把 {@code batchSize} 收到 2，于是 3 行分成两批
     * （第 1 批 = id 1/2，第 2 批 = id 3，末批读不满所以循环收口）：</p>
     * <ol>
     *   <li><b>第 1 批跑完成功</b>：出站、还原、写回一路成功，水位提交到第 1 批末尾（{@code max(id)=2}）；</li>
     *   <li><b>第 2 批在下游写回失败</b>（目标表那一列上加一条只挡 {@code H3} 的约束，链与报文一个字不改）
     *       → 水位**停在 2 没被第 2 批推进**；</li>
     *   <li><b>重跑同一个任务</b>：水位还停在 2，于是重新读到的**只有第 2 批**——断言替身对第 2 批
     *       **只多收到一次**请求、而对第 1 批**一次都没有再收到**（这就是「不重读已成功的批」的判据，
     *       也是「不跳过失败的批」），第 2 批的下游补齐、水位推进到 3。</li>
     * </ol>
     *
     * <p>断言里两个数分开写：第 1 批「零次」与第 2 批「一次」——只看总数会漏掉「重读了第 1 批 +
     * 跳过了第 2 批」这种正好抵消的错法。</p>
     */
    @Test
    void rerunningContinuesFromTheUnfinishedBatchAndNeverRereadsTheSucceededOne() throws Exception {
        httpResponse.set("[{\"code\":\"PO-1\",\"handle\":\"H1\"},{\"code\":\"PO-2\",\"handle\":\"H2\"},"
                + "{\"code\":\"PO-3\",\"handle\":\"H3\"}]");
        Job job = job(JOB_ID);
        // batchSize 收到 2：3 行 → 第 1 批 id 1/2、第 2 批 id 3
        List<Action> actions = b6Chain(2);
        // 只挡 H3（第 2 批那一行）的约束：第 1 批照常写回，坏掉的是第 2 批的**写回那一步**
        customerJdbc.execute("alter table po_receipt add constraint ck_h3 check (handle <> 'H3')");

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        // ---- 第一次运行：第 1 批成功、第 2 批失败，水位停在两批之间 ----
        JobRun first = runNumber(1);
        assertThat(first.getStatus()).isEqualTo("FAILED");
        // 两批各一次请求，出站都成功——失败发生在**下游写回**，不是出站
        assertThat(httpRequests).as("第一趟两批各发一次").hasSize(2);
        assertThat(stepsAt(first.getId(), STEP_OUTLET)).as("两批的出站都成功（失败在下游）")
                .hasSize(2).allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        assertThat(watermarkPuts).as("水位只提交了第 1 批末尾那一次（max(id)=2）——第 2 批失败没有推进它")
                .containsExactly(2L);
        assertThat(customerJdbc.queryForList("select order_no from po_receipt order by order_no", String.class))
                .as("第 1 批写进去了，第 2 批一行都没写").containsExactly("PO-1", "PO-2");

        // 两批各自的批号：拿它当"这一批有没有被重读"的判据（批号由读库那一批当场生成）。
        // 出站那两行按落库顺序 = 第 1 批、第 2 批；失败那一批的批号同时留在失败步上（写回那一步）。
        List<String> firstBatches = stepsAt(first.getId(), STEP_OUTLET).stream()
                .map(MessageStep::getBatchId).toList();
        assertThat(firstBatches).as("两批各一个批号").hasSize(2);
        List<MessageStep> failedWrites = stepsAt(first.getId(), STEP_WRITE).stream()
                .filter(step -> "FAILED".equals(step.getStatus())).toList();
        assertThat(failedWrites).as("第 2 批的写回失败步带着第 2 批的批号")
                .isNotEmpty()
                .allSatisfy(step -> assertThat(step.getBatchId()).isEqualTo(firstBatches.get(1)));

        // ---- 修好目标库，重跑同一个任务：水位还停在 2，所以只会读到第 2 批 ----
        customerJdbc.execute("alter table po_receipt drop constraint ck_h3");
        httpRequests.clear();
        run(job, actions);

        JobRun second = runNumber(2);
        // 「重跑只从第 2 批继续」的判据拆成三个数写：
        //   ① 重跑这一段总共只发了 1 次请求——第 1 批一次都没有再发、第 2 批只多一次
        //   ② 那 1 次出站落在**失败的那一批**上（批号 ≠ 第 1 批那个）
        //   ③ 报文里只有第 2 批那一行
        assertThat(httpRequests)
                .as("重跑只多发了 1 次请求——第 1 批一次都没再发、第 2 批只多一次").hasSize(1);
        List<MessageStep> rerunOutlets = stepsAt(second.getId(), STEP_OUTLET);
        assertThat(rerunOutlets).as("重跑只发出站一次（只有未完成的那一批）").hasSize(1)
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        assertThat(rerunOutlets.getFirst().getBatchId())
                .as("重跑那一批不是第 1 批（%s）——已成功的那一批没有被重读", firstBatches.get(0))
                .isNotEqualTo(firstBatches.get(0));
        assertThat(stepsAt(second.getId(), STEP_WRITE)).as("第 2 批的下游补齐：只有那一批的消息在写")
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("SUCCESS"));
        assertThat(lastRequest.get()).as("重跑发出去的是第 2 批自己那一份（只有 id=3 那一行）")
                .isEqualTo("{\"items\":[{\"order_no\":\"PO-3\",\"id\":3}]}");
        assertThat(watermarkPuts).as("重跑停在两批之间接着走：第一次提交 2、重跑提交 3（没有重复提交 2）")
                .containsExactly(2L, 3L);
        assertThat(customerJdbc.queryForList("select order_no from po_receipt order by order_no", String.class))
                .as("第 1 批没有被重写、第 2 批补上了").containsExactly("PO-1", "PO-2", "PO-3");
        assertThat(customerJdbc.queryForList("select handle from po_receipt order by order_no", String.class))
                .as("回写值正确").containsExactly("H1", "H2", "H3");
        assertThat(second.getStatus()).isEqualTo("SUCCESS");
        assertThat(runs).as("重跑是一次新的运行").hasSize(2);
        assertThat(first.getStatus()).as("原来那次运行仍是 FAILED（重跑不改写历史）").isEqualTo("FAILED");
        assertThat(steps.values()).as("#147：整条链上不再有 batch: 前缀的摘要行")
                .allSatisfy(step -> assertThat(step.getMessageId()).doesNotStartWith("batch:"));
    }

    // ---------- 脚手架 ----------

    /** B6 形状那条链：`READ_SQL → FIELD_MAP → AGGREGATE → HTTP_CALL → ONE_TO_MANY(还原) → FIELD_MAP → WRITE_UPSERT`。 */
    private List<Action> b6Chain() {
        return b6Chain(10);
    }

    /**
     * 同上，但 {@code batchSize} 由调用方定——源表 3 行时 {@code batchSize: 2} 就切出两批
     * （第 1 批 2 行、第 2 批 1 行），用来考「重跑不重读已成功的批」。
     */
    private List<Action> b6Chain(int batchSize) {
        return List.of(
                action(STEP_READ, ActionKind.READ_SQL, Map.of("dataSourceId", DB_DS, "businessKey", "order_no",
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", batchSize)),
                action(STEP_MAP_BEFORE, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", STEP_READ, "source", "order_no", "target", "order_no"),
                        Map.of("sourceStep", STEP_READ, "source", "id", "target", "id")))),
                action(STEP_AGGREGATE, ActionKind.AGGREGATE, Map.of("bodyField", "items")),
                action(STEP_OUTLET, ActionKind.HTTP_CALL, Map.of("dataSourceId", HTTP_DS,
                        "path", "/register", "method", "POST")),
                action(STEP_RESTORE, ActionKind.ONE_TO_MANY, Map.of("mode", "restore",
                        "sourceStep", STEP_OUTLET, "resultKeyField", "code", "attachAs", "handle")),
                action(STEP_MAP_AFTER, ActionKind.FIELD_MAP, Map.of("rules", List.of(
                        Map.of("sourceStep", STEP_READ, "source", "order_no", "target", "order_no"),
                        Map.of("sourceStep", STEP_RESTORE, "source", "handle", "target", "handle")))),
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

    /** 第 n 次执行（按运行标识排序）。 */
    private JobRun runNumber(int n) {
        List<JobRun> ordered = runs.values().stream().sorted(Comparator.comparing(JobRun::getId)).toList();
        assertThat(ordered).as("第 %d 次执行已经落库", n).hasSizeGreaterThanOrEqualTo(n);
        return ordered.get(n - 1);
    }

    private List<MessageStep> stepsAt(Long runId, int step) {
        return steps.values().stream()
                .filter(s -> runId.equals(s.getRunId()) && s.getStep() == step)
                .sorted(Comparator.comparing(MessageStep::getId))
                .toList();
    }
}
