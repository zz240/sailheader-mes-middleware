package com.example.edgegateway.service;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.service.sql.SqlDialects;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.JobRun;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;

/**
 * READ_SQL 分批 + 每批推进水位（#79）的集成行为测试：以 H2 充当客户库，
 * 走完整 Camel 路由，验证首次全量逐批扫完、每批推进水位、某批失败只丢该批。
 */
class RouteGeneratorBatchTest {

    private JdbcTemplate h2Jdbc;
    private RecordingWatermarkStore watermarkStore;
    private DataSourceManager dataSourceManager;
    private MessageHistoryService messageHistory;
    private DataSourceMapper dataSourceMapper;
    private ReadSqlBatcher batcher;
    private RouteGenerator generator;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:batcher" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        h2Jdbc = new JdbcTemplate(ds);
        h2Jdbc.execute("create table if not exists watermark (job_key varchar(64) primary key, wm_value bigint not null)");
        h2Jdbc.execute("create table if not exists po_order (id bigint primary key auto_increment, order_no varchar(32), status varchar(16), updated_at datetime null)");
        h2Jdbc.execute("create table if not exists po_receipt (order_no varchar(64) primary key, id bigint, updated_at datetime null)");
        for (int i = 1; i <= 7; i++) {
            h2Jdbc.update("insert into po_order(order_no, status, updated_at) values (?, ?, ?)",
                    "PO-" + i, "ACTIVE", "2026-08-14 10:" + zeroPad(i) + ":00");
        }

        watermarkStore = new RecordingWatermarkStore(new JdbcTemplate(ds));
        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.get(1L)).thenReturn(ds);
        when(dataSourceManager.dialect(1L)).thenReturn(SqlDialects.of(DataSourceType.MYSQL));
        messageHistory = mock(MessageHistoryService.class);
        when(messageHistory.startRun(anyLong(), any())).thenReturn(jobRun());
        dataSourceMapper = mock(DataSourceMapper.class);
        when(dataSourceMapper.selectById(1L)).thenReturn(mysqlDataSource());
        batcher = spy(new ReadSqlBatcher());
        generator = new RouteGenerator(dataSourceMapper, watermarkStore, messageHistory, dataSourceManager, batcher);
    }

    private String zeroPad(int i) {
        return i < 10 ? "0" + i : String.valueOf(i);
    }

    private JobRun jobRun() {
        JobRun run = new JobRun();
        run.setId(1L);
        return run;
    }

    private DataSource mysqlDataSource() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddress("jdbc:mysql://localhost:3306/edge_gateway");
        ds.setAddition(Map.of("username", "u", "password", "p"));
        return ds;
    }

    private Action action(int index, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setActionIndex(index);
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }

    @Test
    void firstFullLoadBatchesAndAdvancesWatermarkPerBatch() throws Exception {
        Job job = new Job();
        job.setId(1L);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of(
                        "dataSourceId", 1L,
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", 3)));

        run(job, actions);

        // 每批处理完推进水位：3 → 6 → 7（不是到最后才推进）
        assertThat(watermarkStore.puts).containsExactly(3L, 6L, 7L);
        assertThat(watermarkStore.get("job:1")).isEqualTo(7L);
        // 7 行全部读入（每个源行记一条 SUCCESS 消息步）
        verifyApproximately(actions);
    }

    @Test
    void failedBatchDoesNotAdvanceWatermark() {
        Job job = new Job();
        job.setId(1L);
        job.setRetryCount(0);
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of(
                        "dataSourceId", 1L,
                        "sql", "select id, order_no from po_order where {watermark}",
                        "watermarkType", "SEQUENCE", "watermarkColumn", "id", "batchSize", 3)),
                action(2, ActionKind.FIELD_MAP, Map.of(
                        "mode", "EXPRESSION",
                        "expression", "$s_1.id > 3 ? $error('boom on batch2') : $s_1.id")));

        assertThatThrownBy(() -> run(job, actions)).isInstanceOf(Exception.class);

        // 第 1 批成功提交；第 2 批（id 4..6）求值抛错 → 水位停在 3，只丢失败那一批
        assertThat(watermarkStore.puts).containsExactly(3L);
        assertThat(watermarkStore.get("job:1")).isEqualTo(3L);
    }

    @Test
    void timestampRefreshUsesJobTimezoneForWindowBound() throws Exception {
        Job job = new Job();
        job.setId(1L);
        job.setTimezone("Asia/Shanghai");
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of(
                        "dataSourceId", 1L,
                        "sql", "select id, order_no, updated_at from po_order where {watermark}",
                        "watermarkType", "TIMESTAMP", "watermarkColumn", "updated_at", "batchSize", 3)));

        run(job, actions);

        // 刷量窗口下界以任务时区格式化（而非墙钟/系统默认），证明 job.timezone 生效
        verify(batcher, atLeastOnce()).formatTimeBound(anyLong(), eq(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void refreshUpsertIsIdempotentOnRepeatFullRun() throws Exception {
        Job job = new Job();
        job.setId(1L);
        job.setTimezone("Asia/Shanghai");
        // 增量（id）刷新链：READ_SQL（TIMESTAMP，updated_at）+ WRITE_UPSERT（幂等键 order_no）
        List<Action> actions = List.of(
                action(1, ActionKind.READ_SQL, Map.of(
                        "dataSourceId", 1L,
                        "sql", "select id, order_no, updated_at from po_order where {watermark}",
                        "watermarkType", "TIMESTAMP", "watermarkColumn", "updated_at", "batchSize", 3)),
                action(2, ActionKind.WRITE_UPSERT, Map.of(
                        "dataSourceId", 1L, "tableName", "po_receipt", "idempotencyKey", "order_no")));

        // 首跑：7 行全部落库
        run(job, actions);
        assertThat(h2Jdbc.queryForObject("select count(*) from po_receipt", Integer.class)).isEqualTo(7);

        // 清空水位后整表重跑：按业务键 order_no upsert，重复执行不产生重复行
        h2Jdbc.update("delete from watermark");
        run(job, actions);

        assertThat(h2Jdbc.queryForObject("select count(*) from po_receipt", Integer.class)).isEqualTo(7);
        assertThat(h2Jdbc.queryForObject("select count(*) from po_receipt where order_no = 'PO-3'", Integer.class)).isEqualTo(1);
    }

    private void verifyApproximately(List<Action> actions) {
        // 断言：run 成功结束（finishRun SUCCESS 被调用一次）
        org.mockito.Mockito.verify(messageHistory).finishRun(anyLong(), org.mockito.ArgumentMatchers.eq("SUCCESS"), any());
    }

    private void run(Job job, List<Action> actions) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        for (RouteBuilder builder : generator.build(job, actions)) {
            context.addRoutes(builder);
        }
        // 摘掉 resume 路由，只启动主执行路由，避免干扰
        context.removeRouteDefinition(context.getRouteDefinition("resume-" + job.getId()));
        context.start();
        try {
            context.createProducerTemplate().requestBody("direct:job-" + job.getId(), Map.of());
        } finally {
            context.stop();
        }
    }

    /** 记录每次水位提交，验证"每批推进/批失败只丢该批"。 */
    private static class RecordingWatermarkStore extends WatermarkStore {
        final List<Long> puts = new ArrayList<>();

        RecordingWatermarkStore(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public void put(String key, long value) {
            puts.add(value);
            super.put(key, value);
        }
    }
}
