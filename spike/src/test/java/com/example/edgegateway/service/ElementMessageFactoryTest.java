package com.example.edgegateway.service;

import com.example.edgegateway.service.ElementMessageFactory.ElementSpec;
import com.example.edgegateway.service.ElementMessageFactory.InitializedMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「一批元素变成 N 条已初始化消息」这个 module 的直接单测（#139）。
 *
 * <p>只测外部行为：传进去的元素与规则 → 出来的消息（标识、业务键、父消息标识、批内序号、消息体）。
 * 不测内部怎么生成、也不经过 Camel 路由——抽这个 module 的全部意义就是这层能单独跑
 * （同类先例：{@code ReadSqlBatcherTest}）。</p>
 */
class ElementMessageFactoryTest {

    /* ── 读库那一档：一个元素一条消息，无父消息、无批内序号 ─────────────── */

    @Test
    void readBatchMessageGetsItsOwnIdAndExtractedBusinessKey() {
        Map<String, Object> row = Map.of("orderNo", "PO-1", "qty", 3);

        InitializedMessage message = ElementMessageFactory.of(row, ElementSpec.read("orderNo", null));

        assertThat(message.messageId()).as("每条消息有自己的标识").isNotBlank();
        assertThat(message.businessKey()).isEqualTo("PO-1");
        assertThat(message.parentMessageId()).as("读库那条路没有父消息").isNull();
        assertThat(message.batchSeq()).as("读取批里「第几条」没有额外语义：留空，不编 0").isNull();
        assertThat(message.body()).isSameAs(row);
    }

    @Test
    void twoElementsNeverShareAMessageId() {
        InitializedMessage first = ElementMessageFactory.of(Map.of("orderNo", "PO-1"), ElementSpec.read("orderNo", null));
        InitializedMessage second = ElementMessageFactory.of(Map.of("orderNo", "PO-2"), ElementSpec.read("orderNo", null));

        assertThat(first.messageId()).isNotEqualTo(second.messageId());
    }

    /** 重投入口那条路：业务键已经算好了，压过配置（重投的是失败步的输入快照，未必还提得出业务键）。 */
    @Test
    void carriedBusinessKeyWinsOverTheConfiguredField() {
        InitializedMessage message = ElementMessageFactory.of(
                Map.of("orderNo", "PO-NEW"), ElementSpec.read("orderNo", "PO-FROM-ORIGINAL-RUN"));

        assertThat(message.businessKey()).isEqualTo("PO-FROM-ORIGINAL-RUN");
    }

    /* ── 拆分那一档：批内序号从 1 起，每条带父消息标识 ─────────────────── */

    @Test
    void splitNumbersEachChildFromOneInElementOrder() {
        List<Object> elements = List.of(
                Map.of("code", "C-1"),
                Map.of("code", "C-2"),
                Map.of("code", "C-3"));

        List<InitializedMessage> messages = ElementMessageFactory.all(elements,
                ElementSpec.split("code", "parent-uuid", Map.of("orderNo", "SO-1"), false));

        assertThat(messages).hasSize(3);
        assertThat(messages).extracting(InitializedMessage::batchSeq).containsExactly(1, 2, 3);
        assertThat(messages).extracting(InitializedMessage::businessKey).containsExactly("C-1", "C-2", "C-3");
        assertThat(messages).allSatisfy(message ->
                assertThat(message.parentMessageId()).as("子消息记得自己是从哪条消息拆出来的").isEqualTo("parent-uuid"));
        assertThat(messages).extracting(InitializedMessage::messageId).doesNotHaveDuplicates();
    }

    @Test
    void splitWithoutKeepingParentFieldsLeavesTheElementUntouched() {
        Map<String, Object> element = Map.of("code", "C-1");

        List<InitializedMessage> messages = ElementMessageFactory.all(List.of(element),
                ElementSpec.split("code", "parent-uuid", Map.of("orderNo", "SO-1"), false));

        assertThat(messages.getFirst().body()).isSameAs(element);
    }

    /** 头行分表那一档：外层字段摊平进子消息，子消息自己的同名字段赢（元素是这条消息的主体）。 */
    @Test
    void splitWithKeepParentFieldsFlattensOuterFieldsAndChildWins() {
        Map<String, Object> parent = Map.of("orderNo", "SO-1", "status", "OPEN", "shared", "from-parent");
        Map<String, Object> element = Map.of("code", "C-1", "shared", "from-child");

        List<InitializedMessage> messages = ElementMessageFactory.all(List.of(element),
                ElementSpec.split("code", "parent-uuid", parent, true));

        assertThat(messages.getFirst().body()).isEqualTo(Map.of(
                "orderNo", "SO-1",
                "status", "OPEN",
                "shared", "from-child",
                "code", "C-1"));
    }

    /** 元素不是一个对象时（标量数组）不硬包一层：包成 {"value": …} 是无中生有。 */
    @Test
    void splitKeepsScalarElementsAsTheyAre() {
        List<InitializedMessage> messages = ElementMessageFactory.all(List.of("C-1", "C-2"),
                ElementSpec.split("code", "parent-uuid", Map.of("orderNo", "SO-1"), true));

        assertThat(messages).extracting(InitializedMessage::body).containsExactly("C-1", "C-2");
        assertThat(messages).extracting(InitializedMessage::businessKey).containsExactly(null, null);
    }

    /* ── 业务键提取（原先挂在 MessageHistoryService 上，跟着这一层搬过来） ── */

    @Test
    void businessKeyReadsDotPath() {
        assertThat(ElementMessageFactory.businessKey(Map.of("a", Map.of("b", "PO-1")), "a.b")).isEqualTo("PO-1");
        assertThat(ElementMessageFactory.businessKey(Map.of("orderNo", "PO-2"), "orderNo")).isEqualTo("PO-2");
        assertThat(ElementMessageFactory.businessKey(Map.of("orderNo", "PO-3"), null)).isNull();
        assertThat(ElementMessageFactory.businessKey(Map.of("orderNo", "PO-4"), "missing")).isNull();
    }

    /* ── 批号：每执行一次现生成一个 ───────────────────────────────────── */

    @Test
    void everyBatchGetsItsOwnId() {
        String first = ElementMessageFactory.newBatchId();
        String second = ElementMessageFactory.newBatchId();

        assertThat(first).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }
}
