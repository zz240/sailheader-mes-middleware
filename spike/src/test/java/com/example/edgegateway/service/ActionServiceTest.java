package com.example.edgegateway.service;

import com.example.edgegateway.config.FieldMappingConfig;
import com.example.edgegateway.config.MqConsumeConfig;
import com.example.edgegateway.config.MqPublishConfig;
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

class ActionServiceTest {

    private ActionMapper actionMapper;
    private JobMapper jobMapper;
    private DataSourceMapper dataSourceMapper;
    private MappingHistoryService mappingHistoryService;
    private RouteLifecycle routeLifecycle;
    private ActionService service;

    @BeforeEach
    void setUp() {
        actionMapper = mock(ActionMapper.class);
        jobMapper = mock(JobMapper.class);
        dataSourceMapper = mock(DataSourceMapper.class);
        mappingHistoryService = mock(MappingHistoryService.class);
        routeLifecycle = mock(RouteLifecycle.class);
        service = new ActionService(actionMapper, jobMapper, dataSourceMapper, mappingHistoryService, routeLifecycle);
        when(actionMapper.selectList(any())).thenReturn(List.of());
    }

    private ActionRequest readRequest(Integer index, Long dsId) {
        return new ActionRequest("read", ActionKind.READ_SQL, index,
                new ReadSqlConfig(dsId, "SELECT * FROM po_order", null, null, null, null));
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

    /**
     * 非法日期格式串在**保存**这一步就被拦下（#111 验收二）。
     *
     * <p>拦它的是与「校验配置」同一份实现（{@code FieldMappingConfig.problems()}）：非法格式串在运行时
     * 必炸（{@code JsonataFunctions.formatDate} 的 {@code ofPattern} 写在所有 try 之外），不能等任务
     * 跑起来才发现。草稿校验那边报不报错是那一组 fixture 的事，这里只看保存这道门。</p>
     */
    @Test
    void rejectsIllegalDatePattern() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);

        ActionRequest request = new ActionRequest("map", ActionKind.FIELD_MAP, 2,
                FieldMappingConfig.from(Map.of("rules", List.of(Map.of(
                        "source", "createdTime", "target", "createdDate",
                        "transform", "DATE", "datePattern", "ISO-8601")))));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日期格式串不合法: ISO-8601");
    }

    @Test
    void fieldMappingSaveRecordsMappingHistory() {        Job job = new Job();
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
                new MqConsumeConfig(1L, "q", null, null, null, null));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("链首");
    }

    // ---------- MQ_PUBLISH bodySourceStep 显式必填（防静默发错） ----------

    @Test
    void rejectsMqPublishWithoutBodySourceStep() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        ActionRequest request = new ActionRequest("publish", ActionKind.MQ_PUBLISH, 2,
                new MqPublishConfig(3L, null, "edge.notify", "notify.q", null));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bodySourceStep");
    }

    @Test
    void rejectsMqPublishBodySourceStepNotInChain() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        Action existing = new Action();
        existing.setId(10L);
        existing.setJobId(1L);
        existing.setActionIndex(2);
        when(actionMapper.selectList(any())).thenReturn(List.of(existing));
        ActionRequest request = new ActionRequest("publish", ActionKind.MQ_PUBLISH, 3,
                new MqPublishConfig(3L, null, "edge.notify", "notify.q", 1));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bodySourceStep=1");
    }

    @Test
    void rejectsMqPublishBodySourceStepNotUpstream() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        ActionRequest request = new ActionRequest("publish", ActionKind.MQ_PUBLISH, 3,
                new MqPublishConfig(3L, null, "edge.notify", "notify.q", 3));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("执行顺序更早");
    }

    @Test
    void addsMqPublishWithValidBodySourceStep() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
        Action existing = new Action();
        existing.setId(10L);
        existing.setJobId(1L);
        existing.setActionIndex(2);
        when(actionMapper.selectList(any())).thenReturn(List.of(existing));
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);
        when(dataSourceMapper.selectById(3L)).thenReturn(new DataSource());

        Action saved = service.add(1L, new ActionRequest("publish", ActionKind.MQ_PUBLISH, 3,
                new MqPublishConfig(3L, null, "edge.notify", "notify.q", 2)));

        assertThat(saved.getType()).isEqualTo(ActionKind.MQ_PUBLISH);
        assertThat(saved.getAddition()).containsEntry("bodySourceStep", 2);
    }

    // ---------- FIELD_MAP sourceStep 链一致性（#89 T1 写时校验） ----------

    private ActionRequest mapRequest(Integer index, Integer sourceStep) {
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        if (sourceStep != null) {
            rule.put("sourceStep", sourceStep);
        }
        rule.put("source", "order_no");
        rule.put("target", "sheet_no");
        rule.put("transform", "STRING");
        return new ActionRequest("map", ActionKind.FIELD_MAP, index,
                FieldMappingConfig.from(Map.of("rules", List.of(rule))));
    }

    private Action existingAction(int index, ActionKind kind) {
        Action action = new Action();
        action.setId((long) index);
        action.setJobId(1L);
        action.setName(kind.name().toLowerCase());
        action.setType(kind);
        action.setActionIndex(index);
        action.setAddition(Map.of());
        return action;
    }

    private void jobExists() {
        Job job = new Job();
        job.setId(1L);
        when(jobMapper.selectById(1L)).thenReturn(job);
    }

    @Test
    void rejectsFieldMapSourceStepNotInChain() {
        jobExists();

        assertThatThrownBy(() -> service.add(1L, mapRequest(2, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStep=1")
                .hasMessageContaining("不存在");
    }

    @Test
    void rejectsFieldMapSourceStepNotEarlier() {
        jobExists();

        assertThatThrownBy(() -> service.add(1L, mapRequest(2, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStep=3")
                .hasMessageContaining("执行顺序更早");
    }

    @Test
    void rejectsFieldMapSourceStepNotInChainOnUpdate() {
        jobExists();
        when(actionMapper.selectById(5L)).thenReturn(existingAction(2, ActionKind.FIELD_MAP));

        assertThatThrownBy(() -> service.update(5L, mapRequest(2, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStep=1")
                .hasMessageContaining("不存在");
    }

    @Test
    void rejectsFieldMapGroupSourceStepNotInChain() {
        jobExists();
        Map<String, Object> group = Map.of(
                "sourceStep", 2, "source", "items", "target", "lines", "transform", "STRING",
                "children", List.of(Map.of("source", "code", "target", "code", "transform", "STRING")));
        ActionRequest request = new ActionRequest("map", ActionKind.FIELD_MAP, 3,
                FieldMappingConfig.from(Map.of("rules", List.of(group))));

        assertThatThrownBy(() -> service.add(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lines")
                .hasMessageContaining("sourceStep=2");
    }

    @Test
    void addsFieldMapWithEarlierSourceStep() {
        jobExists();
        when(actionMapper.selectList(any())).thenReturn(List.of(existingAction(1, ActionKind.READ_SQL)));
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);

        Action saved = service.add(1L, mapRequest(2, 1));

        assertThat(saved.getType()).isEqualTo(ActionKind.FIELD_MAP);
    }

    @Test
    void addsFieldMapWithJobInputSourceStep() {
        jobExists();
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);

        // sourceStep=0 是任务输入，始终合法
        Action saved = service.add(1L, mapRequest(1, 0));

        assertThat(saved.getType()).isEqualTo(ActionKind.FIELD_MAP);
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

        verify(routeLifecycle).rebuildIfEnabled(1L);
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

        verify(routeLifecycle).rebuildIfEnabled(1L);
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

        verify(routeLifecycle).rebuildIfEnabled(1L);
    }

    @Test
    void delegatesRebuildRegardlessOfStatus() {
        Job job = new Job();
        job.setId(1L);
        job.setStatus(0);
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(actionMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.insert(any(Action.class))).thenReturn(1);
        when(dataSourceMapper.selectById(7L)).thenReturn(new DataSource());

        service.add(1L, readRequest(1, 7L));

        // 是否重建由 RouteLifecycle 按启用状态判定；此处只验证委托
        verify(routeLifecycle).rebuildIfEnabled(1L);
    }
}
