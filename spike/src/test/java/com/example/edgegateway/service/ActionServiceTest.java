package com.example.edgegateway.service;

import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.MqConsumeConfig;
import com.example.edgegateway.config.ReadSqlConfig;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.mapper.JobMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.Job;
import com.example.edgegateway.web.dto.ActionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ActionServiceTest {

    private ActionMapper actionMapper;
    private JobMapper jobMapper;
    private DataSourceMapper dataSourceMapper;
    private MappingHistoryService mappingHistoryService;
    private RouteRegistry routeRegistry;
    private ActionService service;

    @BeforeEach
    void setUp() {
        actionMapper = mock(ActionMapper.class);
        jobMapper = mock(JobMapper.class);
        dataSourceMapper = mock(DataSourceMapper.class);
        mappingHistoryService = mock(MappingHistoryService.class);
        routeRegistry = mock(RouteRegistry.class);
        service = new ActionService(actionMapper, jobMapper, dataSourceMapper, mappingHistoryService, routeRegistry);
        when(actionMapper.selectList(any())).thenReturn(List.of());
    }

    private ActionRequest readRequest(Integer index, Long dsId) {
        return new ActionRequest("read", ActionKind.READ_SQL, index,
                new ReadSqlConfig(dsId, "SELECT * FROM po_order WHERE {watermark}", null, null));
    }

    @Test
    void addsActionToExistingJob() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenAnswer(inv -> {
            inv.getArgument(0, Action.class).setId(1L);
            return 1;
        });
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());

        Action saved = service.add(1L, readRequest(1, 7L));

        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getJobId()).isEqualTo(1L);
        assertThat(saved.getType()).isEqualTo(ActionKind.READ_SQL);
    }

    @Test
    void rejectsActionWhenJobMissing() {
        when(jobMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.add(99L, readRequest(1, 7L)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void rejectsDuplicateIndexWithinJob() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.add(1L, readRequest(1, 7L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
    }

    @Test
    void rejectsUnknownDataSourceReference() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(dataSourceMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.add(1L, readRequest(1, 99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据源");
    }

    @Test
    void rejectsEmptyFieldMappingRules() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);

        ActionRequest request = new ActionRequest("map", ActionKind.FIELD_MAP, 2,
                FieldMappingConfig.from(Map.of()));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("映射规则");
    }

    @Test
    void fieldMappingSaveRecordsMappingHistory() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);

        ActionRequest request = new ActionRequest("map", ActionKind.FIELD_MAP, 2,
                FieldMappingConfig.from(Map.of("rules", List.of(Map.of(
                        "source", "order_no", "target", "sheet_no", "transform", "STRING")))));

        service.add(1L, request);

        verify(mappingHistoryService).recordFromAction(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void readSqlSaveDoesNotRecordMappingHistory() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());

        service.add(1L, readRequest(1, 7L));

        verify(mappingHistoryService, never()).recordFromAction(any(), any());
    }

    @Test
    void rejectsMqConsumeNotFirstInChain() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        ActionRequest request = new ActionRequest("consume", ActionKind.MQ_CONSUME, 2,
                new MqConsumeConfig(1L, "q", null, null));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("链首");
    }

    // ---------- 动作变更后自动重建路由 ----------

    @Test
    void rebuildsRoutesAfterAddWhenJobEnabled() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());

        service.add(1L, readRequest(1, 7L));

        verify(routeRegistry).enable(1L);
    }

    @Test
    void rebuildsRoutesAfterUpdateWhenJobEnabled() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        Action existing = new Action();
        existing.setId(5L);
        existing.setJobId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectById(5L)).thenReturn(existing);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());

        service.update(5L, readRequest(1, 7L));

        verify(routeRegistry).enable(1L);
    }

    @Test
    void rebuildsRoutesAfterDeleteWhenJobEnabled() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        Action existing = new Action();
        existing.setId(5L);
        existing.setJobId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectById(5L)).thenReturn(existing);

        service.delete(5L);

        verify(routeRegistry).enable(1L);
    }

    @Test
    void doesNotRebuildWhenJobDisabled() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(0);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());

        service.add(1L, readRequest(1, 7L));

        verify(routeRegistry, never()).enable(any());
    }

    @Test
    void rebuildFailureDisablesJobAndThrows() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(1);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());
        doThrow(new IllegalStateException("JSONata 编译失败")).when(routeRegistry).enable(1L);

        assertThatThrownBy(() -> service.add(1L, readRequest(1, 7L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已自动停用");
        assertThat(job.getStatus()).isEqualTo(0);
        verify(jobMapper).updateById(job);
    }
}
