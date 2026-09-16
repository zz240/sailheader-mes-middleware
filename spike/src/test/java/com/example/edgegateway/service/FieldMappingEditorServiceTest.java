package com.example.edgegateway.service;

import com.example.edgegateway.mapping.suggest.SourceField;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
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
 * 目标取「本动作之后最近的 WRITE_UPSERT」。粘进来的一份 JSON（#115）复用同一份样例展开，
 * 推出来的字段与运行记录那条路一致。
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
            new StructureProbeService(actionMapper, mock(DataSourceMapper.class));

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
        assertThat(view.writeTarget().tableName()).isEqualTo("po_order");
        assertThat(view.source()).isNotNull();
        assertThat(view.source().step()).isEqualTo(1);
        assertThat(view.source().stepName()).isEqualTo("读订单");
        assertThat(view.source().fields()).extracting(SourceField::name)
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

    /** 编辑器顶部切默认来源时单独取某一个步骤的字段清单。 */
    @Test
    void sourceFieldsOfAStepReturnsThatStepsFields() {
        Action read = action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7));
        Action map = action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of());
        when(actionMapper.selectList(any())).thenReturn(List.of(read, map));
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any())).thenReturn(List.of(step("SUCCESS", "{\"order_no\":\"PO-1\"}")));

        FieldMappingEditorView.SourceSample sample = service.sourceFields(1L, 1);

        assertThat(sample.step()).isEqualTo(1);
        assertThat(sample.stepName()).isEqualTo("读订单");
        assertThat(sample.fields()).extracting(SourceField::name).containsExactly("order_no");
    }

    @Test
    void sourceFieldsIsNullWithoutHistory() {
        when(actionMapper.selectList(any())).thenReturn(List.of());
        when(jobRunMapper.selectList(any())).thenReturn(List.of());

        FieldMappingEditorView.SourceSample sample = service.sourceFields(1L, 1);

        assertThat(sample.step()).isEqualTo(1);
        assertThat(sample.fields()).isEmpty();
    }

    // ---------------------------------------------------------------- 粘贴 JSON（#115）

    /**
     * 两份样例（一份从运行快照来、一份是粘进来的同一段文本）推出来的字段**逐字一样**：
     * 形状（名称、类型、可空、样例值、数组分组）对得上，就是这一票验收里那条「与探测出来的清单形状一致」。
     */
    @Test
    void pastedSampleExpandsExactlyLikeTheProbedSnapshot() {
        String payload = "{\"order_no\":\"PO-1\",\"data\":{\"items\":[{\"qty\":3}]}}";
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of())));
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any())).thenReturn(List.of(step("SUCCESS", payload)));

        FieldMappingEditorView.SourceSample probed = service.sourceFields(1L, 1);
        FieldMappingEditorView.SourceSample pasted = service.sourceFieldsFromSample(1L, 1, payload);
        FieldMappingEditorView.SourceSample pastedArray = service.sourceFieldsFromSample(1L, 1, "[" + payload + "]");

        assertThat(pasted.fields()).isEqualTo(probed.fields());
        assertThat(pasted.fields()).extracting(SourceField::name)
                .containsExactly("order_no", "data.items[0].qty");
        assertThat(pasted.fields()).anyMatch(f -> f.name().equals("data.items[0].qty")
                && f.type().equals("NUMBER") && f.samples().contains(3));
        // 粘一个数组（里面就这一条）推出来的也是同一份：按第 1 条推，路径就是那一条自己的路径
        assertThat(pastedArray.fields()).isEqualTo(probed.fields());
        assertThat(pastedArray.sampledFirstItem()).isTrue();
        // 运行快照那条路不做「按第几条推」的说明：那句话只说粘贴那一边
        assertThat(probed.sampledFirstItem()).isFalse();
        assertThat(pasted.sampledFirstItem()).isFalse();
    }

    /**
     * 数组那条路推的是**第 1 条自己**的路径（`order_no`）；运行快照那条路把整份数组展开成
     * `[0].order_no`（{@code StructureProbeService.walk} 给数组元素加一段 `[0]`）。
     *
     * <p>这一处**故意不一样**：`[0].order_no` 这种没有名字的数组前缀编辑器认不出它属于哪个数组
     * （`source-picker.ts` 的 `owningArrayOf` 得到空串，界面上会多出一个没有名字的数组），配出来的来源
     * 路径用不了。探测那条路的行为一个字不动（#115 验收六），所以差异留在这里、写清楚，而不是顺手改它。</p>
     */
    @Test
    void pastedArrayIsReRootedOnTheFirstItemWhileTheSnapshotProbeKeepsItsPrefix() {
        String payload = "{\"order_no\":\"PO-1\"}";
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7))));
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any())).thenReturn(List.of(step("SUCCESS", "[" + payload + "]")));

        FieldMappingEditorView.SourceSample probed = service.sourceFields(1L, 1);
        FieldMappingEditorView.SourceSample pasted = service.sourceFieldsFromSample(1L, 1, "[" + payload + "]");

        assertThat(probed.fields()).extracting(SourceField::name).containsExactly("[0].order_no");
        assertThat(pasted.fields()).extracting(SourceField::name).containsExactly("order_no");
    }

    /** 粘的是一个数组：按第 1 条推，并且把「按第 1 条推的」这件事告诉界面（sampledFirstItem）。 */
    @Test
    void pastedArrayIsInferredFromTheFirstItem() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7))));

        FieldMappingEditorView.SourceSample sample = service.sourceFieldsFromSample(1L, 1,
                "[{\"order_no\":\"PO-1\",\"data\":{\"items\":[{\"qty\":3}]}},"
                        + "{\"order_no\":\"PO-2\",\"extra\":true}]");

        assertThat(sample.fields()).extracting(SourceField::name)
                .containsExactly("order_no", "data.items[0].qty");
        assertThat(sample.fields()).anyMatch(f -> f.name().equals("order_no")
                && f.samples().equals(List.of("PO-1")));
        assertThat(sample.sampledFirstItem()).isTrue();
    }

    /** 清单归到请求里那个来源步骤：界面上的步骤名也从这一步的动作名字来。 */
    @Test
    void pastedSampleIsAttributedToTheStepItWasAskedFor() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of())));

        FieldMappingEditorView.SourceSample sample = service.sourceFieldsFromSample(1L, 1, "{\"order_no\":\"PO-1\"}");

        assertThat(sample.step()).isEqualTo(1);
        assertThat(sample.stepName()).isEqualTo("读订单");
    }

    /**
     * 收不下的时候说清怎么改：解析不了 / 粘的不是对象也不是数组 / 空数组 / 空文本 / 没给来源。
     *
     * <p>来源那一条是 #115 特别点名的：来源为空会让基于样例的那几条检查静默全部不判，
     * 所以宁可在这一步就拒掉。这几条与目标那条粘贴路共用同一份实现（#118）。</p>
     */
    @Test
    void rejectsSamplesThatCannotBeUsed() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7))));

        assertThatThrownBy(() -> service.sourceFieldsFromSample(1L, 1, "order_no=PO-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("这段 JSON 解析不了");
        assertThatThrownBy(() -> service.sourceFieldsFromSample(1L, 1, "\"PO-1\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("收对象或数组");
        assertThatThrownBy(() -> service.sourceFieldsFromSample(1L, 1, "12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("收对象或数组");
        assertThatThrownBy(() -> service.sourceFieldsFromSample(1L, 1, "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一条数据都没有");
        assertThatThrownBy(() -> service.sourceFieldsFromSample(1L, 1, "[1,2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 条不是一个对象");
        assertThatThrownBy(() -> service.sourceFieldsFromSample(1L, 1, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("还没有粘 JSON");
        assertThatThrownBy(() -> service.sourceFieldsFromSample(1L, null, "{\"order_no\":\"PO-1\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("默认来源");
    }

    // ---------------------------------------------------------------- 目标字段清单也能粘（#118）

    /**
     * 目标那一侧走的也是同一份样例展开：粘一份目标数据推出来的字段，与拿同一份数据当运行快照探出来的
     * **逐字一样**——编辑器那边两边都走 `targetFieldsFromPaths`，所以路径、类型、数组分组（`items[0].x`）
     * 都能用。
     */
    @Test
    void pastedSampleGivesTargetFieldsExactlyLikeTheProbedOne() {
        String payload = "{\"order_no\":\"PO-1\",\"items\":[{\"materialCode\":\"M1\",\"qty\":3}]}";
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7))));
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any())).thenReturn(List.of(step("SUCCESS", payload)));

        FieldMappingEditorView.SourceSample probed = service.sourceFields(1L, 1);
        FieldMappingEditorView.TargetSample pasted = service.targetFieldsFromSample(payload);

        assertThat(pasted.fields()).isEqualTo(probed.fields());
        assertThat(pasted.fields()).extracting(SourceField::name)
                .containsExactly("order_no", "items[0].materialCode", "items[0].qty");
        assertThat(pasted.sampledFirstItem()).isFalse();
    }

    /** 目标这一侧粘数组时同样只按第 1 条推，并把这件事告诉界面。 */
    @Test
    void pastedTargetArrayIsInferredFromTheFirstItem() {
        FieldMappingEditorView.TargetSample sample = service.targetFieldsFromSample(
                "[{\"order_no\":\"PO-1\"},{\"order_no\":\"PO-2\",\"remark\":\"第二条才有\"}]");

        assertThat(sample.fields()).extracting(SourceField::name).containsExactly("order_no");
        assertThat(sample.sampledFirstItem()).isTrue();
    }

    /**
     * 必填推不出来（#118 验收四）：一份 JSON 里没有 NOT NULL 这个信息，所以推出来的字段 nullable 一律 true
     * ——编辑器那一侧 `requiredOf` 据此把它们都当不必填，界面上写明这一点。连库探测那条路照旧按列给。
     */
    @Test
    void pastedTargetFieldsAreAllNullableBecauseJsonCannotSayRequired() {
        FieldMappingEditorView.TargetSample sample = service.targetFieldsFromSample("{\"order_no\":\"PO-1\"}");

        assertThat(sample.fields()).allMatch(SourceField::nullable);
    }
}
