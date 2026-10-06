package com.example.edgegateway.service;

import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobValidationServiceTest {

    private JobService jobService;
    private ActionService actionService;
    private DataSourceMapper dataSourceMapper;
    private ConnectionTester connectionTester;
    private StructureProbeService probeService;
    private ReadOnlySqlValidator sqlValidator;
    private JobValidationService service;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        actionService = mock(ActionService.class);
        dataSourceMapper = mock(DataSourceMapper.class);
        connectionTester = mock(ConnectionTester.class);
        probeService = mock(StructureProbeService.class);
        sqlValidator = mock(ReadOnlySqlValidator.class);
        service = new JobValidationService(jobService, actionService, dataSourceMapper,
                connectionTester, probeService, sqlValidator);
    }

    private Action remote(String sql, Map<String, Object> addition) {
        Action action = new Action();
        action.setName("read");
        action.setType(ActionKind.READ_SQL);
        action.setActionIndex(1);
        Map<String, Object> add = new java.util.HashMap<>(addition);
        add.put("dataSourceId", 7L);
        if (sql != null) {
            add.put("sql", sql);
        }
        action.setAddition(add);
        return action;
    }

    private Action mqConsume(Map<String, Object> addition) {
        Action action = new Action();
        action.setName("consume");
        action.setType(ActionKind.MQ_CONSUME);
        action.setActionIndex(1);
        Map<String, Object> add = new java.util.HashMap<>(addition);
        add.put("dataSourceId", 7L);
        add.put("queue", "q");
        action.setAddition(add);
        return action;
    }

    private Action fieldMap(int index, int sourceStep) {
        Action action = new Action();
        action.setName("map");
        action.setType(ActionKind.FIELD_MAP);
        action.setActionIndex(index);
        action.setAddition(Map.of("rules", List.of(Map.of(
                "sourceStep", sourceStep, "source", "order_no", "target", "sheet_no", "transform", "STRING"))));
        return action;
    }

    private Action writeUpsert(int index) {
        Action action = new Action();
        action.setName("write");
        action.setType(ActionKind.WRITE_UPSERT);
        action.setActionIndex(index);
        action.setAddition(Map.of("dataSourceId", 7L, "tableName", "po_order", "idempotencyKey", "order_no"));
        return action;
    }

    @BeforeEach
    void defaults() {
        when(jobService.get(1L)).thenReturn(new Job());
        when(dataSourceMapper.selectById(7L)).thenReturn(mysqlDs());
        when(connectionTester.test(any())).thenReturn(new ConnectionTester.TestResult(true, "ok"));
        when(probeService.probeFromDb(any(), any(), anyBoolean())).thenReturn(
                new com.example.edgegateway.mapping.suggest.SourceStructure("db:po_order",
                        List.of(new com.example.edgegateway.mapping.suggest.SourceField(
                                "order_no", "VARCHAR", false, List.of("PO-1")))));
    }

    private DataSource mysqlDs() {
        DataSource ds = new DataSource();
        ds.setId(7L);
        ds.setType(DataSourceType.MYSQL);
        ds.setAddress("jdbc:mysql://localhost:3306/x");
        ds.setAddition(Map.of("username", "u", "password", "p"));
        return ds;
    }

    @Test
    void rejectsJobWithoutActions() {
        when(actionService.list(1L)).thenReturn(List.of());

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("没有动作"));
    }

    @Test
    void rejectsMultiStatementSql() {
        when(actionService.list(1L)).thenReturn(
                List.of(remote("SELECT * FROM po_order; DROP TABLE po_order", Map.of())));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("多语句"));
    }

    @Test
    void rejectsWriteKeywordSql() {
        when(actionService.list(1L)).thenReturn(
                List.of(remote("UPDATE po_order SET status=1", Map.of())));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("写语句"));
    }

    @Test
    void passesWhenConnectionAndSqlValid() {
        when(actionService.list(1L)).thenReturn(
                List.of(remote("SELECT * FROM po_order", Map.of())));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void reportsConnectionFailureAsError() {
        when(actionService.list(1L)).thenReturn(
                List.of(remote("SELECT * FROM po_order", Map.of())));
        when(connectionTester.test(any())).thenReturn(new ConnectionTester.TestResult(false, "timeout"));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.scope().equals("data-source"));
    }

    @Test
    void warnsWhenFanoutHasRoutingKey() {
        when(actionService.list(1L)).thenReturn(List.of(
                mqConsume(Map.of("exchangeType", "fanout", "routingKey", "order.#"))));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.scope().equals("action")
                && w.field().equals("consume") && w.message().contains("fanout"));
    }

    @Test
    void noFanoutWarningWhenRoutingKeyAbsentOrTypeNotFanout() {
        when(actionService.list(1L)).thenReturn(List.of(
                mqConsume(Map.of("exchangeType", "fanout")),
                mqConsume(Map.of("exchangeType", "direct", "routingKey", "order.#"))));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).isEmpty();
    }

    // ---------- FIELD_MAP sourceStep 链一致性（#89 T3a /validate） ----------

    @Test
    void reportsDanglingMappingSourceStepAsError() {
        when(actionService.list(1L)).thenReturn(List.of(
                remote("SELECT * FROM po_order", Map.of()),
                fieldMap(2, 5)));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.scope().equals("mapping")
                && e.message().contains("sourceStep=5"));
    }

    @Test
    void reportsMappingSourceStepNotEarlierAsError() {
        when(actionService.list(1L)).thenReturn(List.of(fieldMap(2, 2)));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("执行顺序更早"));
    }

    @Test
    void passesWhenMappingSourceStepIsEarlier() {
        when(actionService.list(1L)).thenReturn(List.of(
                remote("SELECT * FROM po_order", Map.of()),
                fieldMap(2, 1)));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void passesLegalReadMapWriteChain() {
        when(actionService.list(1L)).thenReturn(List.of(
                remote("SELECT * FROM po_order", Map.of()),
                fieldMap(2, 1),
                writeUpsert(3)));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ---------- 拆分动作（#140）：不引用数据源，也不该被报缺 dataSourceId ----------

    private Action split(int index, int sourceStep) {
        Action action = new Action();
        action.setName("split");
        action.setType(ActionKind.SPLIT);
        action.setActionIndex(index);
        action.setAddition(Map.of(
                "sourceStep", sourceStep, "sourcePath", "lines", "elementKey", "itemCode"));
        return action;
    }

    @Test
    void splitActionIsNotReportedAsMissingDataSource() {
        // 真机第一次跑这条链就是被这条误报拦下的：拆分与字段映射一样在链上干活，不连任何库
        when(actionService.list(1L)).thenReturn(List.of(
                remote("SELECT * FROM po_order", Map.of()),
                split(2, 1)));

        ValidationResult result = service.validate(1L);

        assertThat(result.errors())
                .as("拆分动作不该被报「缺少 dataSourceId」")
                .noneMatch(e -> e.message().contains("dataSourceId"));
        assertThat(result.valid()).isTrue();
    }

    @Test
    void splitActionWithDanglingSourceStepIsReported() {
        when(actionService.list(1L)).thenReturn(List.of(
                remote("SELECT * FROM po_order", Map.of()),
                split(2, 9)));

        ValidationResult result = service.validate(1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.message().contains("sourceStep=9"));
    }
}
