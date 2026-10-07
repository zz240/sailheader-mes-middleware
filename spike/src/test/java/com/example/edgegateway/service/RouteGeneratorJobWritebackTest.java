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
import com.example.edgegateway.service.sql.SqlDialects;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.support.H2MapperTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #88 端到端回写：跑真实 Camel 链，断言任务行 {@code last_run_status/last_run_at/last_error}
 * 随运行结果更新（真库：内嵌 H2 + 测试 schema.sql）。覆盖消费主路由、重放/断点入口与调度投递。
 */
@H2MapperTest
class RouteGeneratorJobWritebackTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private JobMapper jobMapper;

    @Autowired
    private JobRunMapper jobRunMapper;

    @Autowired
    private MessageStepMapper messageStepMapper;

    @Autowired
    private ActionMapper actionMapper;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    private DataSourceManager dataSourceManager;
    private WatermarkStore watermarkStore;

    @BeforeEach
    void setUp() {
        dataSourceManager = mock(DataSourceManager.class);
        // 水位持久化（MySQL 方言）不在本用例范围：mock 掉，聚焦任务行回写
        watermarkStore = mock(WatermarkStore.class);
    }

    @Test
    void consumerRouteSuccessWritesJobLastRun() throws Exception {
        Job job = insertJob();
        List<Action> actions = consumeThenMap(insertRabbitDataSource(), "bom.to.edge");

        DefaultCamelContext context = consumerRouteContext(job, actions);
        try {
            context.createProducerTemplate().requestBody(triggerOf(job), "{\"orderNo\":\"PO-1\"}");
        } finally {
            context.stop();
        }

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastRunAt()).isNotNull();
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    void consumerRouteFailureWritesJobLastRunWithError() throws Exception {
        Job job = insertJob();
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of(
                        "dataSourceId", insertRabbitDataSource(), "queue", "mes.to.edge")),
                action(2, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));
        stubBrokenCustomerDataSource();

        DefaultCamelContext context = consumerRouteContext(job, actions);
        try {
            // 消费主路由 handled=true：失败不抛回 broker，但任务结果仍要落库
            context.createProducerTemplate().requestBody(triggerOf(job), "{\"order_no\":\"PO-1\"}");
        } finally {
            context.stop();
        }

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("FAILED");
        assertThat(updated.getLastError()).isNotBlank();
        assertThat(updated.getLastRunAt()).isNotNull();
    }

    /** 重放入口（direct:job-N）：手动执行 MQ 消费型任务 / 断点重放共用，尾部同样是统一回写。 */
    @Test
    void replayEntrySuccessWritesJobLastRun() throws Exception {
        Job job = insertJob();
        List<Action> actions = consumeThenMap(insertRabbitDataSource(), "bom.to.edge");

        DefaultCamelContext context = replayContext(job, actions);
        try {
            context.createProducerTemplate().requestBody("direct:job-" + job.getId(), Map.of("orderNo", "PO-1"));
        } finally {
            context.stop();
        }

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastRunAt()).isNotNull();
    }

    @Test
    void replayEntryFailureWritesJobLastRunWithError() throws Exception {
        Job job = insertJob();
        List<Action> actions = List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of(
                        "dataSourceId", insertRabbitDataSource(), "queue", "mes.to.edge")),
                action(2, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 2L, "tableName", "po_receipt", "idempotencyKey", "order_no")));
        stubBrokenCustomerDataSource();
        DefaultCamelContext context = replayContext(job, actions);

        try {
            assertThatThrownBy(() -> context.createProducerTemplate().requestBody("direct:job-" + job.getId(),
                    Map.of("order_no", "PO-1")))
                    .isInstanceOf(Exception.class);
        } finally {
            context.stop();
        }

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("FAILED");
        assertThat(updated.getLastError()).isNotBlank();
    }

    /** 调度投递：JobScheduler 触发即 {@link JobTrigger#trigger(Long, Object)}，与手动执行共用轮询入口与统一回写。 */
    @Test
    void scheduledDeliveryWritesJobLastRun() throws Exception {
        Job job = insertJob();
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of("dataSourceId", 1L,
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "businessKey", "order_no")),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "order_no", "target", "order_no")))));

        DefaultCamelContext context = new DefaultCamelContext();
        javax.sql.DataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:jobWriteback;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (java.sql.Connection c = h2.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("create table if not exists po_order(id bigint primary key, order_no varchar(64))");
            st.execute("merge into po_order(id, order_no) values (1, 'PO-1')");
        }
        when(dataSourceManager.get(1L)).thenReturn(h2);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        context.getRegistry().bind("edge-ds-1", h2);
        try {
            addRoutes(context, job, actions);
            context.start();
            // 调度投递不携带输入（JobScheduler → JobTrigger.trigger(jobId, null)）
            new JobTrigger(context).trigger(job.getId(), null);
        } finally {
            context.stop();
        }

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastRunAt()).isNotNull();
    }

    /** 断点重投（direct:job-N-resume）也是一次运行：从失败步重放，尾部同样统一回写。 */
    @Test
    void resumeRouteWritesJobLastRun() throws Exception {
        Job job = insertJob();
        List<Action> actions = consumeThenMap(insertRabbitDataSource(), "bom.to.edge");

        DefaultCamelContext context = replayContext(job, actions);
        try {
            context.createProducerTemplate().request("direct:job-" + job.getId() + "-resume", ex -> {
                ex.getMessage().setBody(Map.of("orderNo", "PO-1"));
                ex.setProperty(MessageHistoryService.RESUME_FROM, 2);
            });
        } finally {
            context.stop();
        }

        Job updated = jobMapper.selectById(job.getId());
        assertThat(updated.getLastRunStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getLastRunAt()).isNotNull();
    }

    /** 消费主路由：把 rabbitmq 入口换成 direct 触发，验证 MQ_CONSUME 链首（handled=true）的真实链路。 */
    private DefaultCamelContext consumerRouteContext(Job job, List<Action> actions) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        addRoutes(context, job, actions);
        AdviceWith.adviceWith(context, "job-" + job.getId(), a -> a.replaceFromWith(triggerOf(job)));
        context.start();
        return context;
    }

    /** 重放入口：摘掉消费主路由后启动，走 direct:job-N（手动执行/断点重放）。 */
    private DefaultCamelContext replayContext(Job job, List<Action> actions) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        addRoutes(context, job, actions);
        if (actions.getFirst().getType() == ActionKind.MQ_CONSUME) {
            context.removeRouteDefinition(context.getRouteDefinition("job-" + job.getId()));
        }
        context.start();
        return context;
    }

    private void addRoutes(DefaultCamelContext context, Job job, List<Action> actions) throws Exception {
        MessageHistoryService history = new MessageHistoryService(jobRunMapper, messageStepMapper, jobMapper,
                actionMapper, new ObjectMapper(), context, new JobLastRunWriter(jobMapper));
        RouteGenerator generator = new RouteGenerator(dataSourceMapper, watermarkStore, history,
                dataSourceManager, new ReadSqlBatcher());
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
    }

    private String triggerOf(Job job) {
        return "direct:consume-" + job.getId();
    }

    private List<Action> consumeThenMap(Long mqDataSourceId, String queue) {
        return List.of(
                action(1, ActionKind.MQ_CONSUME, Map.of("dataSourceId", mqDataSourceId, "queue", queue)),
                action(2, ActionKind.FIELD_MAP, Map.of("rules",
                        List.of(Map.of("sourceStep", 1, "source", "orderNo", "target", "order_no")))));
    }

    private void stubBrokenCustomerDataSource() throws Exception {
        javax.sql.DataSource broken = mock(javax.sql.DataSource.class);
        when(broken.getConnection()).thenThrow(new java.sql.SQLException("connection broken"));
        when(dataSourceManager.get(2L)).thenReturn(broken);
        when(dataSourceManager.dialect(2L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
    }

    private Job insertJob() {
        Job job = new Job();
        job.setSiteId(1L);
        job.setName("job-writeback-chain-" + SEQ.incrementAndGet());
        job.setStatus(1);
        jobMapper.insert(job);
        return job;
    }

    private Long insertRabbitDataSource() {
        DataSource ds = new DataSource();
        ds.setSiteId(1L);
        ds.setName("mq-" + SEQ.incrementAndGet());
        ds.setType(DataSourceType.RABBITMQ);
        ds.setAddition(Map.of("host", "localhost", "port", 5672));
        dataSourceMapper.insert(ds);
        return ds.getId();
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setType(kind);
        action.setName(switch (kind) {
            case READ_SQL -> "read";
            case FIELD_MAP -> "map";
            case WRITE_UPSERT -> "upsert";
            case HTTP_CALL -> "call";
            case MQ_CONSUME -> "consume";
            case MQ_PUBLISH -> "publish";
            case ONE_TO_MANY -> "one-to-many";
            case AGGREGATE -> "aggregate";
        });
        action.setAddition(addition);
        return action;
    }
}
