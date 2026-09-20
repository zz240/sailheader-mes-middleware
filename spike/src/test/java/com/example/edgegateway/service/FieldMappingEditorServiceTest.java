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
import com.example.edgegateway.model.MappingSampleKind;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.web.dto.FieldMappingEditorView;
import com.example.edgegateway.web.dto.MappingSampleRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 新版编辑器（#99）的服务端支持：来源取「链上更早那个动作最近一次成功运行的输出」，
 * 目标取「本动作之后最近的 WRITE_UPSERT」。粘进来的一份 JSON（#115）复用同一份样例展开，
 * 推出来的字段与运行记录那条路一致；**存过的那份压过探测**、按「任务 + 用途 + 归属」取回（#119）。
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
    private final MappingSampleService sampleService = mock(MappingSampleService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StructureProbeService probeService =
            new StructureProbeService(actionMapper, mock(DataSourceMapper.class));

    private final FieldMappingEditorService service = new FieldMappingEditorService(
            actionMapper, jobRunMapper, messageStepMapper, probeService, dataSourceService, sampleService,
            objectMapper);

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
                .hasMessageContaining("不支持结构探测");
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
        // 粘的那份把**原文**一起带回来（#119）：保存时手里还握着它，才能原样写回库里
        assertThat(pasted.json()).isEqualTo(payload);
        assertThat(pastedArray.json()).isEqualTo("[" + payload + "]");
        // 运行快照那条路也给原文（#116 试算要拿它跑）：同一份数据的另一种写法。**它仍然不落库**——
        // 「存不存」由界面按 sourceKey 判来历（探测的不存），跟这一格在不在没关系
        assertThat(probed.json()).isEqualTo(payload);
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
        // 这一次粘的那份：记号是 `paste`（与库里存过的那份 `stored` 分开，#119）
        assertThat(pasted.sourceKey()).isEqualTo("paste");
        assertThat(pasted.json()).isEqualTo(payload);
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

    // ---------------------------------------------------------------- 粘一次就留着（#119）

    /**
     * 打开时**存过的那份压过探测**（#119 验收一、二）：这一步存过一份粘的 → 用存的那份，原文一起回来
     * （保存时还要原样写回去），运行快照那条路上有什么都不看。
     */
    @Test
    void storedSampleWinsOverTheRunSnapshotWhenOpening() {
        String stored = "{\"order_no\":\"PO-9\",\"kept\":true}";
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of())));
        when(sampleService.stored(1L, MappingSampleKind.SOURCE, 1L)).thenReturn(stored);
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any()))
                .thenReturn(List.of(step("SUCCESS", "{\"from_run\":\"x\"}")));

        FieldMappingEditorView view = service.context(1L, 12L, null);

        assertThat(view.source().step()).isEqualTo(1);
        assertThat(view.source().stepName()).isEqualTo("读订单");
        // 界面上要写明的那个标记：这份是「粘的（已存）」，不是「来自 … 最近一次成功运行」
        assertThat(view.source().sourceKey()).isEqualTo("stored");
        assertThat(view.source().json()).isEqualTo(stored);
        assertThat(view.source().fields()).extracting(SourceField::name).containsExactly("order_no", "kept");
    }

    /**
     * 冷启动 / MQ 打头的任务：链上还没有更早的动作时，来源那一格算「任务输入（0）」——存过的那份照样
     * 回得来（#119 验收一）。这正是 #115 / #118 存在的理由：那批任务一点都探不出来源清单。
     */
    @Test
    void storedSampleForTaskInputComesBackEvenWithoutAnEarlierAction() {
        String stored = "[{\"order_no\":\"PO-1\"},{\"order_no\":\"PO-2\",\"extra\":1}]";
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "字段映射", ActionKind.FIELD_MAP, Map.of())));
        when(sampleService.stored(1L, MappingSampleKind.SOURCE, 0L)).thenReturn(stored);

        FieldMappingEditorView view = service.context(1L, 11L, null);

        assertThat(view.source()).isNotNull();
        assertThat(view.source().step()).isZero();
        assertThat(view.source().stepName()).isNull(); // 「任务输入」那一步没有动作
        assertThat(view.source().sourceKey()).isEqualTo("stored");
        assertThat(view.source().sampledFirstItem()).isTrue();
        assertThat(view.source().fields()).extracting(SourceField::name).containsExactly("order_no");
    }

    /** 没存过的那些任务一个字不变：没有更早的动作、库里也没存 → 左边空着（与 #119 之前一样）。 */    @Test
    void withoutAStoredSampleTheProbePathIsUnchanged() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "字段映射", ActionKind.FIELD_MAP, Map.of())));
        when(jobRunMapper.selectList(any())).thenReturn(List.of());

        FieldMappingEditorView view = service.context(1L, 11L, null);

        assertThat(view.source()).isNull();
        verify(sampleService).stored(1L, MappingSampleKind.SOURCE, 0L);
    }

    /**
     * 归属键是（任务 + 步骤），不是「哪个动作」：读同一步的两个编辑器看到的是**同一份**（#119 验收六）。
     *
     * <p>下面是同一格（任务 1 · 步骤 2）的两种读法——编辑已有的那个动作，与给一个准备占用同样序号的新动作
     * 打开编辑器。落在同一格上，而不是各粘各的。</p>
     */
    @Test
    void bothMappingsReadingTheSameStepSeeTheSameStoredSample() {
        String stored = "{\"order_no\":\"PO-9\"}";
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "上游映射", ActionKind.FIELD_MAP, Map.of()),
                action(14L, 3, "字段映射乙", ActionKind.FIELD_MAP, Map.of())));
        when(sampleService.stored(1L, MappingSampleKind.SOURCE, 2L)).thenReturn(stored);

        FieldMappingEditorView existing = service.context(1L, 14L, null);
        FieldMappingEditorView draft = service.context(1L, null, 3);

        assertThat(existing.source().step()).isEqualTo(2);
        assertThat(draft.source().step()).isEqualTo(2);
        assertThat(existing.source().json()).isEqualTo(stored);
        assertThat(draft.source().json()).isEqualTo(stored);
    }

    /** 库里那一行坏了（不是 JSON / 推不出字段）不该让编辑器打不开：退回探测那条路，与没存过一样。 */    @Test
    void brokenStoredSampleFallsBackToTheProbe() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of())));
        when(sampleService.stored(1L, MappingSampleKind.SOURCE, 1L)).thenReturn("order_no=PO-1");
        when(jobRunMapper.selectList(any())).thenReturn(List.of(run(100L)));
        when(messageStepMapper.selectList(any()))
                .thenReturn(List.of(step("SUCCESS", "{\"order_no\":\"PO-1\"}")));

        FieldMappingEditorView view = service.context(1L, 12L, null);

        assertThat(view.source().sourceKey()).isEqualTo("sample");
        // 退回探测那条路之后手里那份照样带着原文（试算要拿它跑），它仍然不落库
        assertThat(view.source().json()).isEqualTo("{\"order_no\":\"PO-1\"}");
    }

    /** 目标那一格同理（#119 验收一、二、六）：写目标动作存过 → 用存的那份，连库探测那条路不发。 */
    @Test
    void storedTargetSampleComesBackWithTheContext() {
        String stored = "{\"order_no\":\"PO-1\",\"items\":[{\"materialCode\":\"M1\"}]}";
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of()),
                action(13L, 3, "写订单", ActionKind.WRITE_UPSERT,
                        Map.of("dataSourceId", 9, "tableName", "po_order"))));
        when(sampleService.stored(1L, MappingSampleKind.TARGET, 13L)).thenReturn(stored);
        when(jobRunMapper.selectList(any())).thenReturn(List.of());
        when(messageStepMapper.selectList(any())).thenReturn(List.of());

        FieldMappingEditorView view = service.context(1L, 12L, null);

        assertThat(view.writeTarget().actionId()).isEqualTo(13L);
        assertThat(view.targetSample().sourceKey()).isEqualTo("stored");
        assertThat(view.targetSample().json()).isEqualTo(stored);
        assertThat(view.targetSample().fields()).extracting(SourceField::name)
                .containsExactly("order_no", "items[0].materialCode");
    }

    /** 没存过的（或没有写目标的）任务：这一格是 null，编辑器那一侧照旧去连库探测。 */
    @Test
    void withoutAStoredTargetSampleTheTargetStaysProbeOnly() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of()),
                action(13L, 3, "写订单", ActionKind.WRITE_UPSERT,
                        Map.of("dataSourceId", 9, "tableName", "po_order"))));
        when(jobRunMapper.selectList(any())).thenReturn(List.of());
        when(messageStepMapper.selectList(any())).thenReturn(List.of());

        FieldMappingEditorView view = service.context(1L, 12L, null);

        assertThat(view.targetSample()).isNull();
        verify(sampleService).stored(1L, MappingSampleKind.TARGET, 13L);
    }

    /** 目标那份粘出来的原文也一起带回来（#119：保存时写回库里的就是它）。 */
    @Test
    void pastedTargetSampleCarriesItsRawText() {
        String payload = "{\"order_no\":\"PO-1\"}";

        assertThat(service.targetFieldsFromSample(payload).json()).isEqualTo(payload);
    }

    /**
     * 点保存那一次（#119 规则 2）：带原文 = 手里是粘的 → 存下来；不带原文 = 手里是探测来的 → 把这一格
     * 删掉。归属由请求给（来源 = 步骤、目标 = 写目标动作 id）。
     */
    @Test
    void savingWritesThePastedCellAndClearsTheProbedOne() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7)),
                action(12L, 2, "字段映射", ActionKind.FIELD_MAP, Map.of()),
                action(13L, 3, "写订单", ActionKind.WRITE_UPSERT, Map.of("dataSourceId", 9))));
        when(actionMapper.selectById(13L)).thenReturn(
                action(13L, 3, "写订单", ActionKind.WRITE_UPSERT, Map.of("dataSourceId", 9)));

        service.saveSamples(1L, new MappingSampleRequest(
                new MappingSampleRequest.SourceSampleSave(1, "{\"order_no\":\"PO-1\"}"),
                new MappingSampleRequest.TargetSampleSave(13L, null)));

        verify(sampleService).save(1L, MappingSampleKind.SOURCE, 1L, "{\"order_no\":\"PO-1\"}");
        verify(sampleService).save(1L, MappingSampleKind.TARGET, 13L, null);
    }

    /** 编辑器对某一边没话可说时（来源侧压根没有样例 / 没有写目标动作）库里那一格不动。 */
    @Test
    void savingLeavesASideAloneWhenTheEditorHasNothingToSayAboutIt() {
        service.saveSamples(1L, new MappingSampleRequest(null, null));

        verifyNoInteractions(sampleService);
    }

    /** 一格的身份缺一不可：来源缺步骤、目标缺动作 id 都是参数问题（400），不能静默存。 */
    @Test
    void savingRejectsASampleWithoutItsOwnership() {
        assertThatThrownBy(() -> service.saveSamples(1L, new MappingSampleRequest(
                new MappingSampleRequest.SourceSampleSave(null, "{\"order_no\":\"PO-1\"}"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("默认来源");
        assertThatThrownBy(() -> service.saveSamples(1L, new MappingSampleRequest(null,
                new MappingSampleRequest.TargetSampleSave(null, "{\"order_no\":\"PO-1\"}"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("写目标动作");
    }

    /**
     * 归属还得**落在请求里那个任务之内**（#119 验收六「归属对得上」）：链上没有那一步、或那个动作不是
     * 这个任务的，都是参数问题——不然会写下一行谁都不会去读的样例（甚至清掉别的动作那一格）。
     */
    @Test
    void savingRejectsASampleThatDoesNotBelongToTheJob() {
        when(actionMapper.selectList(any())).thenReturn(List.of(
                action(11L, 1, "读订单", ActionKind.READ_SQL, Map.of("dataSourceId", 7))));
        Action foreign = action(99L, 3, "别人的写动作", ActionKind.WRITE_UPSERT, Map.of());
        foreign.setJobId(2L);
        when(actionMapper.selectById(99L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.saveSamples(1L, new MappingSampleRequest(
                new MappingSampleRequest.SourceSampleSave(9, "{\"order_no\":\"PO-1\"}"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有序号 9 的动作");
        assertThatThrownBy(() -> service.saveSamples(1L, new MappingSampleRequest(null,
                new MappingSampleRequest.TargetSampleSave(99L, "{\"order_no\":\"PO-1\"}"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("找不到");
        verifyNoInteractions(sampleService);
    }
}
