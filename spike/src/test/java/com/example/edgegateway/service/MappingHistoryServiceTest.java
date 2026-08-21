package com.example.edgegateway.service;

import com.example.edgegateway.config.FieldMappingRule;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.mapper.MappingHistoryMapper;
import com.example.edgegateway.mapping.TransformType;
import com.example.edgegateway.mapping.suggest.HistoryMapping;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.model.MappingHistory;
import com.example.edgegateway.model.SyncDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MappingHistoryServiceTest {

    private MappingHistoryMapper historyMapper;
    private JobMapper jobMapper;
    private ActionMapper actionMapper;
    private DataSourceMapper dataSourceMapper;
    private MappingHistoryService service;

    @BeforeEach
    void setUp() {
        historyMapper = mock(MappingHistoryMapper.class);
        jobMapper = mock(JobMapper.class);
        actionMapper = mock(ActionMapper.class);
        dataSourceMapper = mock(DataSourceMapper.class);
        service = new MappingHistoryService(historyMapper, jobMapper, actionMapper, dataSourceMapper);
    }

    private Action remote(Long dsId, String sql, int index) {
        Action action = new Action();
        action.setType(sql != null ? ActionKind.READ_SQL : ActionKind.MQ_CONSUME);
        action.setActionIndex(index);
        Map<String, Object> addition = new java.util.HashMap<>();
        addition.put("dataSourceId", dsId);
        if (sql != null) {
            addition.put("sql", sql);
        }
        action.setAddition(addition);
        return action;
    }

    private DataSource mysqlDs() {
        DataSource ds = new DataSource();
        ds.setId(7L);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition(Map.of("url", "jdbc:mysql://localhost:3306/x",
                "username", "u", "password", "p"));
        return ds;
    }

    private DataSource mqDs() {
        DataSource ds = new DataSource();
        ds.setId(8L);
        ds.setType(DataSourceType.RABBITMQ);
        ds.setAddition(Map.of("queue", "mes.to.edge"));
        return ds;
    }

    @Test
    void infersNorthWhenFirstActionIsMysqlRead() {
        when(dataSourceMapper.selectById(7L)).thenReturn(mysqlDs());
        SyncDirection direction = service.inferDirection(
                List.of(remote(7L, "SELECT * FROM po_order WHERE id > {watermark}", 1)));
        assertThat(direction).isEqualTo(SyncDirection.NORTH);
    }

    @Test
    void infersSouthWhenFirstActionIsRabbitConsumer() {
        when(dataSourceMapper.selectById(8L)).thenReturn(mqDs());
        SyncDirection direction = service.inferDirection(
                List.of(remote(8L, null, 1)));
        assertThat(direction).isEqualTo(SyncDirection.SOUTH);
    }

    @Test
    void sourceKeyNorthUsesTableFromSql() {
        when(dataSourceMapper.selectById(7L)).thenReturn(mysqlDs());
        List<Action> actions = List.of(remote(7L, "SELECT * FROM po_order WHERE id > {watermark}", 1));
        assertThat(service.inferSourceKey(actions)).isEqualTo("db:po_order");
    }

    @Test
    void sourceKeySouthUsesQueueName() {
        when(dataSourceMapper.selectById(8L)).thenReturn(mqDs());
        Action action = new Action();
        action.setType(ActionKind.MQ_CONSUME);
        action.setActionIndex(1);
        action.setAddition(Map.of("dataSourceId", 8L, "queue", "mes.to.edge"));
        List<Action> actions = List.of(action);
        assertThat(service.inferSourceKey(actions)).isEqualTo("mq:mes.to.edge");
    }

    @Test
    void sourceKeySouthPrefersActionQueue() {
        Action action = new Action();
        action.setType(ActionKind.MQ_CONSUME);
        action.setActionIndex(1);
        action.setAddition(Map.of("dataSourceId", 8L, "queue", "action.queue"));

        assertThat(service.inferSourceKey(List.of(action))).isEqualTo("mq:action.queue");
    }

    @Test
    void sourceKeyDecoupledFromDirection() {
        // DS-9：入口是 MQ_CONSUME 但方向是 NORTH（客户发 MQ → 消费 → 推 MES HTTP）
        Action action = new Action();
        action.setType(ActionKind.MQ_CONSUME);
        action.setActionIndex(1);
        action.setAddition(Map.of("dataSourceId", 8L, "queue", "customer.in"));

        assertThat(service.inferDirection(List.of(action))).isEqualTo(SyncDirection.SOUTH);
        assertThat(service.inferSourceKey(List.of(action))).isEqualTo("mq:customer.in");

        Job job = new Job();
        job.setId(1L);
        job.setSiteId(5L);
        job.setDirection(SyncDirection.NORTH);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectList(any())).thenReturn(List.of(action));

        service.recordFromAction(1L, List.of(new FieldMappingRule(null,
                "orderNo", "order_no", TransformType.STRING, null, Map.of(), null)));

        // 历史按 job.direction（NORTH）落库，sourceKey 按源动作（mq:customer.in）
        org.mockito.Mockito.verify(historyMapper).insert(org.mockito.ArgumentMatchers.argThat((MappingHistory row) ->
                row.getDirection() == SyncDirection.NORTH
                        && "mq:customer.in".equals(row.getSourceKey())));
    }

    @Test
    void recordFromActionInsertsNewHistory() {
        Job job = new Job();
        job.setId(1L);
        job.setSiteId(5L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(dataSourceMapper.selectById(7L)).thenReturn(mysqlDs());
        when(actionMapper.selectList(any())).thenReturn(
                List.of(remote(7L, "SELECT * FROM po_order", 1)));
        when(historyMapper.selectOne(any())).thenReturn(null);

        service.recordFromAction(1L, List.of(new FieldMappingRule(null,
                "order_no", "sheet_no", TransformType.STRING, null, Map.of(), null)));

        verify(historyMapper).insert(any(MappingHistory.class));
    }

    @Test
    void recordFromActionUpdatesExistingAndIncrementsHitCount() {
        Job job = new Job();
        job.setId(1L);
        job.setSiteId(5L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(dataSourceMapper.selectById(7L)).thenReturn(mysqlDs());
        when(actionMapper.selectList(any())).thenReturn(
                List.of(remote(7L, "SELECT * FROM po_order", 1)));
        MappingHistory existing = new MappingHistory();
        existing.setId(9L);
        existing.setHitCount(3);
        when(historyMapper.selectOne(any())).thenReturn(existing);

        service.recordFromAction(1L, List.of(new FieldMappingRule(null,
                "order_no", "sheet_no", TransformType.STRING, null, Map.of(), null)));

        assertThat(existing.getHitCount()).isEqualTo(4);
        verify(historyMapper).updateById(existing);
    }

    @Test
    void listByScopeConvertsToSuggesterValueObjects() {
        MappingHistory row = new MappingHistory();
        row.setSourceKey("db:po_order");
        row.setSourcePath("order_no");
        row.setTargetName("sheet_no");
        row.setTransform("STRING");
        row.setHitCount(2);
        row.setUpdatedAt(java.time.LocalDateTime.of(2026, 8, 17, 12, 0));
        when(historyMapper.selectList(any())).thenReturn(List.of(row));

        List<HistoryMapping> history = service.listByScope(5L, SyncDirection.NORTH);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).sourcePath()).isEqualTo("order_no");
        assertThat(history.get(0).hitCount()).isEqualTo(2);
    }

    @Test
    void extractTableHandlesQuotedAndSchemaQualifiedNames() {
        assertThat(MappingHistoryService.extractTable("SELECT * FROM po_order WHERE id > 1"))
                .isEqualTo("po_order");
        assertThat(MappingHistoryService.extractTable("select a,b from `erp`.po_order o"))
                .isEqualTo("po_order");
        assertThat(MappingHistoryService.extractTable("SELECT 1")).isNull();
    }
}
