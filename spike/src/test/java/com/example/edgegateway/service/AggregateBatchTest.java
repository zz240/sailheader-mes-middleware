package com.example.edgegateway.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合并的护栏（#142）：判齐、响应形状、按业务键配对、配对结果落 body——纯逻辑，脱离整链单测。
 */
class AggregateBatchTest {

    private static AggregateBatch.Carrier carrier(String messageId, String businessKey, String rowKey, int shares) {
        return new AggregateBatch.Carrier(messageId, businessKey, null, null, Map.of("k", messageId),
                Map.of(), rowKey, shares, false);
    }

    @Test
    void aBatchOfReadRowsIsCompleteWhenEveryRowArrived() {
        AggregateBatch batch = new AggregateBatch();
        batch.add(carrier("m1", "PO-1", "m1", 1));
        batch.add(carrier("m2", "PO-2", "m2", 1));

        assertThat(batch.expected()).isEqualTo(2);
        assertThat(batch.complete()).isTrue();
    }

    @Test
    void sharesAreCountedPerRowNotPerArrival() {
        AggregateBatch batch = new AggregateBatch();
        // 一行拆出 2 条：两条带同一个 rowKey、各自 shares=2 —— 合计仍是 2，不是 4
        batch.add(carrier("c1", "C-1", "parent", 2));
        assertThat(batch.complete()).as("只到了一条，还差一条").isFalse();
        batch.add(carrier("c2", "C-2", "parent", 2));

        assertThat(batch.expected()).isEqualTo(2);
        assertThat(batch.complete()).isTrue();
    }

    @Test
    void anEmptyBatchIsNotComplete() {
        assertThat(new AggregateBatch().complete())
                .as("一批一条消息都没产出（空批）时不算齐——也就不该发")
                .isFalse();
    }

    @Test
    void batchIsAllSkippedOnlyWhenEveryCarrierIsSkipped() {
        AggregateBatch skipped = new AggregateBatch();
        skipped.add(new AggregateBatch.Carrier("m1", "PO-1", null, null, Map.of(), Map.of(), "m1", 1, true));
        assertThat(skipped.allSkipped()).isTrue();

        AggregateBatch mixed = new AggregateBatch();
        mixed.add(new AggregateBatch.Carrier("m1", "PO-1", null, null, Map.of(), Map.of(), "m1", 1, true));
        mixed.add(carrier("m2", "PO-2", "m2", 1));
        assertThat(mixed.allSkipped()).isFalse();
    }

    @Test
    void responseItemsAcceptsTheThreeCommonShapes() {
        assertThat(AggregateBatch.responseItems(List.of("a"))).containsExactly("a");
        assertThat(AggregateBatch.responseItems(Map.of("codes", List.of("a", "b"))))
                .as("与请求体对称的那种「一个数组字段」的形状")
                .containsExactly("a", "b");
        assertThat(AggregateBatch.responseItems(Map.of("handle", "H1")))
                .as("单个对象当只有一项")
                .containsExactly(Map.of("handle", "H1"));
        assertThatThrownBy(() -> AggregateBatch.responseItems(null))
                .as("空响应不猜、也不当空数组")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pairingMatchesByBusinessKeyAndSkipsTheMissingOnes() {
        Map<String, Object> first = Map.of("code", "PO-1", "handle", "H1");
        Map<String, Object> third = Map.of("code", "PO-3", "handle", "H3");
        List<AggregateBatch.Carrier> carriers = List.of(
                carrier("m1", "PO-1", "m1", 1), carrier("m2", "PO-2", "m2", 1));

        Map<String, Object> paired = AggregateBatch.pair(List.of(first, third), "code", carriers);

        assertThat(paired).containsOnlyKeys("m1");
        assertThat(paired.get("m1")).isEqualTo(first);
    }

    @Test
    void pairingComparesStringFormsSoNumericKeysStillMatch() {
        List<AggregateBatch.Carrier> carriers = List.of(carrier("m1", "88.1", "m1", 1));

        Map<String, Object> paired = AggregateBatch.pair(List.of(Map.of("code", 88.1, "handle", "H")), "code", carriers);

        assertThat(paired).containsOnlyKeys("m1");
    }

    @Test
    void attachAddsTheSliceToTheBodyAndRefusesNonObjects() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_no", "PO-1");

        assertThat(AggregateBatch.attach(body, "handle", Map.of("handle", "H1")))
                .as("原有字段留着，配对结果落在 attachAs 那个键上")
                .isEqualTo(Map.of("order_no", "PO-1", "handle", Map.of("handle", "H1")));
        assertThatThrownBy(() -> AggregateBatch.attach("一段文本", "handle", Map.of()))
                .as("body 不是对象时显式失败，不硬包一层把原有字段丢掉")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("handle");
    }
}
