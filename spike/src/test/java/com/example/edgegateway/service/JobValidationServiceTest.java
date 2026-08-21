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
        ds.setAddition(Map.of("url", "jdbc:mysql://localhost:3306/x",
                "username", "u", "password", "p"));
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
                List.of(remote("SELECT * FROM po_order WHERE id > {watermark}", Map.of())));

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
}
