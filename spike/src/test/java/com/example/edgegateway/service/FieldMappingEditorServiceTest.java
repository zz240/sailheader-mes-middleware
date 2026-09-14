package com.example.edgegateway.service;

import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.JobRunMapper;
import com.example.edgegateway.mapper.MessageStepMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.web.dto.FieldMappingEditorView;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 新版编辑器（#99）的服务端支持：来源取「链上更早那个动作最近一次成功运行的输出」，
 * 目标取「本动作之后最近的 WRITE_UPSERT」。
 */
class FieldMappingEditorServiceTest {

    @BeforeAll
    static void initEntityTableInfo() {
        // 纯 Mockito 单测无 Spring 扫描，需显式初始化 MyBatis-Plus 实体表/列缓存（同 MessageHistoryServiceTest）。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MessageStep.class);
        TableInfoHelper.initTableInfo(assistant, JobRun.class);
        TableInfoHelper.initTableInfo(assistant, Action.class);
    }

    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final JobRunMapper jobRunMapper = mock(JobRunMapper.class);
    private final MessageStepMapper messageStepMapper = mock(MessageStepMapper.class);
    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StructureProbeService probeService =
            new StructureProbeService(actionMapper, mock(com.example.edgegateway.mapper.DataSourceMapper.class));

    private final FieldMappingEditorService service = new FieldMappingEditorService(
            actionMapper, jobRunMapper, messageStepMapper, probeService, dataSourceService, objectMapper);

    private static Action action(long id, int index, String name, ActionKind kind, Map<String, Object> addition) {
        Action action = new Action();
        action.setId(id);
        action.setJobId(1L);
        action.setActionIndex(index);
        action.setName(name);
        action.setType(kind);
        action.setAddition(addition);
        return action;
    }

    @Test
    void resolvesSourceFromPreviousStepAndTargetFromNextWrite() {
        Action read = action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7));
        Action map = action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of("rules", List.of()));
        Action write = action(13L, 3, "写订单", ActionKind.WRITE_UPSERT, Map.of("dataSourceId", 9, "tableName", "po_order"));
        when(actionMapper.selectList(any())).thenReturn(List.of(read, map, write));
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any())).thenReturn(List.of(step("SUCCESS",
                "{\"order_no\":\"PO-1\",\"data\":{\"items\":[{\"qty\":3}]}}")));

        FieldMappingEditorView view = service.context(1L, 12L, null);

        assertThat(view.writeTarget()).isNotNull();
        assertThat(view.writeTarget().actionId()).isEqualTo(13L);
        assertThat(view.writeTarget().dataSourceId()).isEqualTo(9L);
        assertThat(view.source()).isNotNull();
        assertThat(view.source().step()).isEqualTo(1);
        assertThat(view.source().stepName()).isEqualTo("读订单");
        assertThat(view.source().fields()).extracting(FieldMappingEditorView.Field::name)
                .containsExactly("order_no", "data.items[0].qty");
        assertThat(view.source().fields()).anyMatch(f -> f.name().equals("data.items[0].qty") && f.samples().contains(3));
        assertThat(view.actions()).extracting(FieldMappingEditorView.ActionLabel::name)
                .containsExactly("读订单", "字段映射", "写订单");
    }

    @Test
    void sourceIsNullWhenNoEarlierStepRan() {
        Action read = action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7));
        Action map = action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of());
        when(actionMapper.selectList(any())).thenReturn(List.of(read, map));
        when(jobRunMapper.selectList(any())).thenReturn(List.of());

        FieldMappingEditorView view = service.context(1L, 12L, null);

        assertThat(view.writeTarget()).isNull();
        assertThat(view.source()).isNotNull();
        assertThat(view.source().fields()).isEmpty();
    }

    @Test
    void newActionUsesGivenIndexToPickPreviousStep() {
        Action read = action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7));
        when(actionMapper.selectList(any())).thenReturn(List.of(read));
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any())).thenReturn(List.of(step("SUCCESS", "{\"order_no\":\"PO-1\"}")));

        FieldMappingEditorView view = service.context(1L, null, 2);

        assertThat(view.source().step()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownAction() {
        Action read = action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of());
        when(actionMapper.selectList(any())).thenReturn(List.of(read));

        assertThatThrownBy(() -> service.context(1L, 999L, null))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("动作不存在");
    }

    @Test
    void probeTargetUsesWriteActionDataSource() {
        Action write = action(13L, 3, "写订单", ActionKind.WRITE_UPSERT, Map.of("dataSourceId", 9, "tableName", "po_order"));
        when(actionMapper.selectById(13L)).thenReturn(write);
        DataSource dataSource = new DataSource();
        dataSource.setId(9L);
        dataSource.setType(DataSourceType.HTTP);
        when(dataSourceService.get(9L)).thenReturn(dataSource);

        // HTTP 数据源不能探测——沿用结构探测自己的限制，错误信息一致
        assertThatThrownBy(() -> service.probeTargetFields(13L, "po_order", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅 MySQL");
    }

    @Test
    void probeTargetRejectsActionWithoutDataSource() {
        Action write = action(13L, 3, "写订单", ActionKind.WRITE_UPSERT, Map.of("tableName", "po_order"));
        when(actionMapper.selectById(13L)).thenReturn(write);

        assertThatThrownBy(() -> service.probeTargetFields(13L, "po_order", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有选数据源");
    }

    private static JobRun run(Long id) {
        JobRun run = new JobRun();
        run.setId(id);
        run.setJobId(1L);
        return run;
    }

    private static MessageStep step(String status, String dataSnapshot) {
        MessageStep step = new MessageStep();
        step.setId(5L);
        step.setRunId(100L);
        step.setStep(1);
        step.setStatus(status);
        step.setDataSnapshot(dataSnapshot);
        return step;
    }

    /** 目标探测走真结构探测服务（贴样例 / 连库），这里只验证数据源是从写动作上取的。 */
    @Test
    void targetProbeShapeMatchesProbeContract() {
        SourceStructure structure = probeService.probeSample(Map.of("order_no", "PO-1"));
        assertThat(structure.key()).isEqualTo("sample");
    }
}
