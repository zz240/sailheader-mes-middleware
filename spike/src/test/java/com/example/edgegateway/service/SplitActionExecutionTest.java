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
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拆分动作（#140 / ADR-0017）的整链行为测试：跑真实的 Camel 链
 * （MQ 重放入口 → 拆分 → 字段映射 → 写库），断言**落库的消息步**符合票面口径。
 *
 * <p>这一票的四条主验收都在这条链上一次跑完看得见：</p>
 * <ul>
 *   <li>拆分动作**自己记一行**消息步（输入是父消息、输出是子消息们）——以前这类动作不记行；</li>
 *   <li>拆出来的每条子消息**五样齐全**：消息标识、业务键、批内序号、父消息标识、批号；</li>
 *   <li>子消息之间是**同级**关系，父消息标识都指向被拆的那条，且**与「保不保外层字段」无关**；</li>
 *   <li>拆完之后照旧**一条一条走**：字段映射与写库各记各的行，业务键与批内序号对得上。</li>
 * </ul>
 *
 * <p>取数与内存表的手法照抄 {@code MessageHistoryExecutionTest}（同一套 MyBatis-Plus 桩），
 * 这样断言打的是「写库那一刻的那一行」，而不是测试自己拼出来的期望结构。</p>
 */
class SplitActionExecutionTest {

    @BeforeAll
    static void initEntityTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageStep.class);
        TableInfoHelper.initTableInfo(assistant, JobRun.class);
        TableInfoHelper.initTableInfo(assistant, Action.class);
        TableInfoHelper.initTableInfo(assistant, Job.class);
    }

    private static final Long JOB_ID = 41L;

    private final DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
    private final WatermarkStore watermarkStore = mock(WatermarkStore.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final JobMapper jobMapper = mock(JobMapper.class);

    private final Map<Long, JobRun> runs = new ConcurrentHashMap<>();
    private final Map<Long, MessageStep> steps = new ConcurrentHashMap<>();

    private long runSeq = 1;
    private long stepSeq = 1;

    private JdbcDataSource customerDb;
    private JdbcTemplate customerJdbc;

    @BeforeEach
    void setUp() {
        customerDb = new JdbcDataSource();
        customerDb.setURL("jdbc:h2:mem:split" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        customerDb.setUser("sa");
        customerJdbc = new JdbcTemplate(customerDb);
        customerJdbc.execute("create table if not exists order_line ("
                + "item_code varchar(64) primary key, order_no varchar(64), line_no int, qty int)");
        when(dataSourceManager.get(1L)).thenReturn(customerDb);
        when(dataSourceManager.get(2L)).thenReturn(customerDb);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        when(dataSourceManager.dialect(2L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        stubConsumeDataSource();
        bindInMemory();
    }

    /**
     * 票面那条端到端验收：`读 → 拆分 → 写库`（此处入口用 MQ 重放路由喂一份报文，读的那一步等价于
     * 「报文进链」）。一份报文里的 `lines` 数组摊成 2 条子消息，逐条映射、逐条落库。
     */
    @Test
    void splitExpandsArrayIntoChildMessagesThatThenRunOneByOne() throws Exception {
        Job job = job(JOB_ID);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, "consume", Map.of("dataSourceId", 3L, "queue", "bom.to.edge")),
                action(2, ActionKind.ONE_TO_MANY, "拆明细", Map.of("mode", "split", 
                        "sourcePath", "lines", "elementKey", "itemCode", "keepParent", true)),
                action(3, ActionKind.FIELD_MAP, "map", Map.of("rules", List.of(
                        Map.of("sourceStep", 2, "source", "itemCode", "target", "item_code"),
                        Map.of("sourceStep", 2, "source", "lineNo", "target", "line_no"),
                        Map.of("sourceStep", 2, "source", "qty", "target", "qty")))),
                action(4, ActionKind.WRITE_UPSERT, "write", Map.of(
                        "dataSourceId", 2L, "tableName", "order_line", "idempotencyKey", "item_code")));
        customerJdbc.execute("insert into order_line(item_code, line_no, qty) values ('IT-1', 1, 3)");

        runSteps(job, actions, parentMessage());
        JobRun run = theRun();
        List<MessageStep> allSteps = stepsOf(run);
        List<MessageStep> splitRows = rowsAt(allSteps, 2);

        // 1) 拆分动作自己记一行：输入 = 父消息，输出 = 子消息们（那片数组）
        assertThat(splitRows).hasSize(1);
        MessageStep splitRow = splitRows.getFirst();
        assertThat(splitRow.getStatus()).isEqualTo("SUCCESS");
        assertThat(splitRow.getInputSnapshot())
                .as("拆分那一行的输入是**父消息**，不是某一个子元素")
                .contains("ORD-9");
        assertThat(splitRow.getDataSnapshot())
                .as("输出是子消息们——也就是那份数组")
                .contains("IT-1").contains("IT-2");

        // 2) 拆出来的每条子消息五样齐全，且互为同级（父消息标识都指向被拆的那条）
        List<MessageStep> mapRows = rowsAt(allSteps, 3);
        assertThat(mapRows).hasSize(2);
        assertThat(mapRows).extracting(MessageStep::getBusinessKey).containsExactly("IT-1", "IT-2");
        assertThat(mapRows).extracting(MessageStep::getBatchSeq).containsExactly(1, 2);
        assertThat(mapRows).extracting(MessageStep::getMessageId).doesNotHaveDuplicates();
        assertThat(mapRows.getFirst().getMessageId())
                .as("子消息有自己的消息标识，不是沿用父消息那一个")
                .isNotEqualTo(splitRow.getMessageId());
        assertThat(mapRows).extracting(MessageStep::getParentMessageId)
                .as("两条子消息都记得自己是从**同一条**父消息拆出来的")
                .containsOnly(splitRow.getMessageId());
        assertThat(mapRows).extracting(MessageStep::getBatchId)
                .as("一次拆分 = 一个批次：两条子消息共用现生成的那个批号")
                .doesNotContainNull()
                .containsOnly(mapRows.getFirst().getBatchId());
        assertThat(splitRow.getBatchId())
                .as("MQ 入口链本来就没有批号（读取批那个号只作兜底），批号诞生在拆分这一步："
                        + "父消息那一行带的是**上游那个号**（此处为空），子消息那一行带新号")
                .isNull();
        assertThat(splitRow.getParentMessageId())
                .as("拆分那一行**既带父消息标识也带批号**（ADR-0017 决策 4）：它自己就是那条父消息，"
                        + "所以父消息标识就是它自己的消息标识——谱系与分组两格都齐")
                .isEqualTo(splitRow.getMessageId());

        // 3) 拆完之后照旧一条一条走：映射结果逐条落库，两行各是各的数据
        assertThat(mapRows).extracting(MessageStep::getDataSnapshot)
                .anySatisfy(d -> assertThat(d).contains("\"line_no\":1"))
                .anySatisfy(d -> assertThat(d).contains("\"line_no\":2"));
        List<MessageStep> writeRows = rowsAt(allSteps, 4);
        assertThat(writeRows).extracting(MessageStep::getBusinessKey).containsExactly("IT-1", "IT-2");
        assertThat(customerJdbc.queryForList("select item_code, line_no, qty from order_line order by line_no"))
                .as("N 条子消息各写各的那一片：头一条那行被它自己那条子消息更新过（幂等键 item_code）")
                .hasSize(2)
                .anySatisfy(row -> assertThat(row).containsEntry("item_code", "IT-1"))
                .anySatisfy(row -> assertThat(row).containsEntry("item_code", "IT-2"));
        assertThat(customerJdbc.queryForObject(
                "select qty from order_line where item_code = 'IT-2'", Integer.class))
                .as("第二条子消息写的是**自己那一片**（qty 5），不是父消息或别的元素那片")
                .isEqualTo(5);
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
    }

    /**
     * 「外层字段是否摊平」**只影响子消息 body，不影响谱系**（票面验收）：同一个来源步骤、
     * 同一条父消息，只把 {@code keepParent} 翻成 {@code false}——子消息里不再有父消息的字段，
     * 但父消息标识、批内序号、批号三样照旧。
     */
    @Test
    void keepParentOnlyChangesChildBodyNotLineage() throws Exception {
        Job job = job(JOB_ID);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, "consume", Map.of("dataSourceId", 3L, "queue", "bom.to.edge")),
                action(2, ActionKind.ONE_TO_MANY, "拆明细", Map.of("mode", "split", 
                        "sourcePath", "lines", "elementKey", "itemCode", "keepParent", false)),
                action(3, ActionKind.FIELD_MAP, "map", Map.of("rules", List.of(
                        Map.of("sourceStep", 2, "source", "itemCode", "target", "item_code"),
                        Map.of("sourceStep", 2, "source", "qty", "target", "qty")))));

        runSteps(job, actions, parentMessage());
        List<MessageStep> allSteps = stepsOf(theRun());
        MessageStep splitRow = rowsAt(allSteps, 2).getFirst();
        List<MessageStep> mapRows = rowsAt(allSteps, 3);

        assertThat(mapRows).extracting(MessageStep::getParentMessageId)
                .as("不摊平外层字段，谱系照样成立：父消息标识仍指向被拆的那条")
                .containsOnly(splitRow.getMessageId());
        assertThat(mapRows).extracting(MessageStep::getBatchSeq).containsExactly(1, 2);
        assertThat(mapRows).extracting(MessageStep::getBatchId).doesNotContainNull();
        assertThat(mapRows).extracting(MessageStep::getDataSnapshot)
                .as("子消息只装自己那一片（元素自己的字段），父消息的 orderNo 不摊进来")
                .allSatisfy(snapshot -> assertThat(snapshot).doesNotContain("ORD-9"));
    }

    /**
     * 外层字段摊平的是**父消息**的字段（不是数组里第一个元素的字段）：来源步骤指向报文那一步，
     * 父消息上带着单号与批次号，子消息里没有这两格——摊平之后**两条**子消息都该拿到它们。
     */
    @Test
    void keepParentFlattensTheParentMessagesOwnFieldsIntoEveryChild() throws Exception {
        Job job = job(JOB_ID);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, "consume", Map.of("dataSourceId", 3L, "queue", "bom.to.edge")),
                action(2, ActionKind.ONE_TO_MANY, "拆明细", Map.of("mode", "split", 
                        "sourceStep", 1, "sourcePath", "lines", "elementKey", "itemCode", "keepParent", true)),
                action(3, ActionKind.FIELD_MAP, "map", Map.of("rules", List.of(
                        Map.of("sourceStep", 2, "source", "itemCode", "target", "item_code"),
                        Map.of("sourceStep", 2, "source", "orderNo", "target", "order_no")))));

        runSteps(job, actions, parentMessage());
        List<MessageStep> mapRows = rowsAt(stepsOf(theRun()), 3);

        assertThat(mapRows).hasSize(2);
        assertThat(mapRows).extracting(MessageStep::getDataSnapshot)
                .as("父消息的 orderNo 两条子消息都摊到了（它不在任何一个元素里）")
                .allSatisfy(snapshot -> assertThat(snapshot).contains("ORD-9"))
                .anySatisfy(snapshot -> assertThat(snapshot).contains("IT-1"))
                .anySatisfy(snapshot -> assertThat(snapshot).contains("IT-2"));
    }

    /**
     * 来源取不到数组时**当场显式失败**，不静默拆出 0 条：链上「配置写错了」要看得见，
     * 而不是跑出一个"成功但一条数据都没动"的运行。
     */
    @Test
    void splitWithUnresolvablePathFailsTheRunAndRecordsAFailedStep() throws Exception {
        Job job = job(JOB_ID);
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, "consume", Map.of("dataSourceId", 3L, "queue", "bom.to.edge")),
                action(2, ActionKind.ONE_TO_MANY, "拆明细", Map.of("mode", "split", 
                        "sourcePath", "noSuchArray", "elementKey", "itemCode", "keepParent", true)),
                action(3, ActionKind.WRITE_UPSERT, "write", Map.of(
                        "dataSourceId", 2L, "tableName", "order_line", "idempotencyKey", "item_code")));

        runSteps(job, actions, parentMessage());
        JobRun run = theRun();
        List<MessageStep> splitRows = rowsAt(stepsOf(run), 2);

        assertThat(run.getStatus())
                .as("取不到数组就是失败，不是「成功但什么都没做」")
                .isEqualTo("FAILED");
        assertThat(splitRows).hasSize(1);
        assertThat(splitRows.getFirst().getStatus()).isEqualTo("FAILED");
        assertThat(splitRows.getFirst().getError())
                .as("错误信息说得清是哪个路径取不到")
                .contains("noSuchArray");
        assertThat(customerJdbc.queryForObject("select count(*) from order_line", Integer.class))
                .as("拆不出来就没有子消息，写库那一步压根不该跑")
                .isZero();
    }

    /**
     * 票面 AC7 那条端到端：**带水位的 `READ_SQL → 拆分 → 写库`** 一次跑完。
     *
     * <p>它单独有一条用例，是因为链上这时**套着两层 split**——读库分批那层（`loopDoWhile` + `split`）
     * 与拆分动作那层。收尾必须由内到外关闭（先关拆分那个、再交水位、最后关循环），否则路由根本建不起来；
     * 走 MQ 重放入口那条用例碰不到这个形态（那里只有一层 split）。</p>
     *
     * <p>用 `$parse()` 把读回来的 JSON 列还原成数组：这正是「SQL `FOR JSON PATH` 聚出来的明细」
     * 那份现成做法（`JsonataFunctions.parseJson`）。</p>
     */
    @Test
    void watermarkedReadThenSplitRunsAsOneChainAndAdvancesWatermark() throws Exception {
        customerJdbc.execute("create table if not exists bom_head ("
                + "id bigint primary key, bom_no varchar(32), lines_json varchar(4000))");
        customerJdbc.update("insert into bom_head(id, bom_no, lines_json) values (?, ?, ?)", 1L, "BOM-1",
                "[{\"itemCode\":\"IT-1\",\"qty\":3},{\"itemCode\":\"IT-2\",\"qty\":5}]");
        Job job = job(JOB_ID);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, "read", Map.of(
                        "dataSourceId", 1L,
                        "sql", "select id, bom_no, lines_json from bom_head where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", 10,
                        "businessKey", "bom_no")),
                // 读回来的是"一行一条报文"：先把 JSON 列还原成数组，再拆
                action(2, ActionKind.FIELD_MAP, "展开报文", Map.of(
                        "mode", "EXPRESSION",
                        "expression", "{\"orderNo\": $s_1.bom_no, \"lines\": $parse($s_1.lines_json)}")),
                action(3, ActionKind.ONE_TO_MANY, "拆明细", Map.of("mode", "split", 
                        "sourceStep", 2, "sourcePath", "lines", "elementKey", "itemCode", "keepParent", true)),
                action(4, ActionKind.FIELD_MAP, "map", Map.of("rules", List.of(
                        Map.of("sourceStep", 3, "source", "itemCode", "target", "item_code"),
                        Map.of("sourceStep", 3, "source", "orderNo", "target", "order_no"),
                        Map.of("sourceStep", 3, "source", "qty", "target", "qty")))),
                action(5, ActionKind.WRITE_UPSERT, "write", Map.of(
                        "dataSourceId", 2L, "tableName", "order_line", "idempotencyKey", "item_code")));

        runSteps(job, actions, Map.of());
        JobRun run = theRun();
        List<MessageStep> allSteps = stepsOf(run);

        assertThat(run.getStatus())
                .as("链跑完应当是成功（失败原因：" + run.getError() + "）")
                .isEqualTo("SUCCESS");
        // 读库那一层：源行一条（bom_head 一行）
        assertThat(rowsAt(allSteps, 1)).hasSize(1);
        // 拆分那一行（父消息）+ 两条子消息各走一步
        MessageStep splitRow = rowsAt(allSteps, 3).getFirst();
        List<MessageStep> mapRows = rowsAt(allSteps, 4);
        assertThat(mapRows).extracting(MessageStep::getBusinessKey).containsExactly("IT-1", "IT-2");
        assertThat(mapRows).extracting(MessageStep::getBatchSeq).containsExactly(1, 2);
        assertThat(mapRows).extracting(MessageStep::getParentMessageId)
                .containsOnly(splitRow.getMessageId());
        // 读库那一步的批号（每读一批一个）从源行继承到拆分那一行，拆分之后才换手
        assertThat(splitRow.getBatchId())
                .as("这一行的批号是**上游读库那一批**的号，不是拆分现生成的")
                .isNotNull()
                .isEqualTo(rowsAt(allSteps, 1).getFirst().getBatchId());
        assertThat(mapRows.getFirst().getBatchId())
                .as("批号在拆分这一步换手：子消息那一批的号与读库那批不同")
                .isNotEqualTo(splitRow.getBatchId());
        // 水位推进（整批成功才推进）：两行都落库，水位推到读完的那一行
        assertThat(customerJdbc.queryForList("select item_code, order_no, qty from order_line order by item_code"))
                .hasSize(2)
                .anySatisfy(row -> assertThat(row).containsEntry("order_no", "BOM-1"));
        verify(watermarkStore).put("job:" + JOB_ID, 1L);
    }

    /** 一份报文：头一行（单号）+ 两行明细。 */
    private Map<String, Object> parentMessage() {
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put("orderNo", "ORD-9");
        parent.put("lines", List.of(
                Map.of("itemCode", "IT-1", "lineNo", 1, "qty", 3),
                Map.of("itemCode", "IT-2", "lineNo", 2, "qty", 5)));
        return parent;
    }

    // ---------- 脚手架 ----------

    private void runSteps(Job job, List<Action> actions, Object input) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        MessageHistoryService history = new MessageHistoryService(
                jobRunMapper, messageStepMapper, jobMapper, actionMapper, new ObjectMapper(), context,
                new JobLastRunWriter(jobMapper));
        RouteGenerator generator = new RouteGenerator(
                dataSourceMapper, watermarkStore, history, dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        // 链首是 MQ_CONSUME 时摘掉真消费路由（测试里不连 broker），入口改用重放路由 direct:job-<id>
        // ——与既有用例同一手法。READ_SQL 链首的主路由本身就是 direct 入口，动了它就没消费者了
        if (actions.getFirst().getType() == ActionKind.MQ_CONSUME) {
            context.removeRouteDefinition(context.getRouteDefinition("job-" + job.getId()));
        }
        // 断点续跑那条路由也照常建、照常启动：它没有读库那层循环，模型建不起来时正好在本用例里暴露
        context.start();
        try {
            context.createProducerTemplate().requestBody("direct:job-" + job.getId(), input);
        } catch (Exception e) {
            // 失败用例：异常按既有语义抛出来即可，断言打的是"落库的那一行"与运行状态
            lastError = e;
        } finally {
            context.stop();
        }
    }

    /** 最近一次 {@link #runSteps} 里 Camel 抛出来的异常（路由建不起来时用它把原因带进断言信息）。 */
    private Exception lastError;

    /** 异常链最里面那一句：Camel 报的常常只是外层壳，真正的原因在最里面。 */
    private String rootCause() {
        if (lastError == null) {
            return "无";
        }
        Throwable t = lastError;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    private JobRun theRun() {
        return runs.values().stream()
                .filter(r -> JOB_ID.equals(r.getJobId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有产生运行记录（Camel 侧原因：" + rootCause() + "）"));
    }

    private List<MessageStep> stepsOf(JobRun run) {
        return steps.values().stream()
                .filter(s -> s.getRunId().equals(run.getId()))
                .collect(Collectors.toList());
    }

    private List<MessageStep> rowsAt(List<MessageStep> runSteps, int step) {
        return runSteps.stream().filter(s -> s.getStep() == step).toList();
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
        when(jobRunMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(runs.values()));
        when(messageStepMapper.insert(any(MessageStep.class))).thenAnswer(inv -> {
            MessageStep step = inv.getArgument(0);
            step.setId(stepSeq++);
            steps.put(step.getId(), step);
            return 1;
        });
        when(messageStepMapper.selectOne(any())).thenReturn(null);
        when(messageStepMapper.selectById(anyLong())).thenAnswer(inv -> steps.get((Long) inv.getArgument(0)));
        when(messageStepMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(steps.values()));
        when(messageStepMapper.updateById(any(MessageStep.class))).thenAnswer(inv -> {
            MessageStep step = inv.getArgument(0);
            steps.put(step.getId(), step);
            return 1;
        });
    }

    private void stubConsumeDataSource() {
        DataSource ds = new DataSource();
        ds.setId(3L);
        ds.setType(DataSourceType.RABBITMQ);
        ds.setName("mes.exchange");
        ds.setAddition(Map.of("host", "localhost", "port", 5672));
        when(dataSourceMapper.selectById(3L)).thenReturn(ds);
    }

    private Job job(Long id) {
        Job job = new Job();
        job.setId(id);
        job.setStatus(1);
        return job;
    }

    private Action action(int index, ActionKind kind, String name, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setName(name);
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }
}
