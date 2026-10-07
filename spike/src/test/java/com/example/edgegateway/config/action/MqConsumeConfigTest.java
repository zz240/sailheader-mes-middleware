package com.example.edgegateway.config.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #81：MQ 消费绑定配置模型——exchangeType（缺省 direct）解析/枚举校验/映射。 */
class MqConsumeConfigTest {

    @Test
    void parsesExchangeType() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of(
                "dataSourceId", 1, "queue", "q", "exchange", "edge",
                "exchangeType", "topic", "routingKey", "order.#"));

        assertThat(config.exchangeType()).isEqualTo("topic");
        assertThat(config.effectiveExchangeType()).isEqualTo("topic");
    }

    @Test
    void absentExchangeTypeDefaultsToDirect() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q"));

        assertThat(config.exchangeType()).isNull();
        assertThat(config.effectiveExchangeType()).isEqualTo("direct");
    }

    @Test
    void blankExchangeTypeStillDefaultsToDirect() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of(
                "dataSourceId", 1, "queue", "q", "exchangeType", " "));

        assertThat(config.effectiveExchangeType()).isEqualTo("direct");
    }

    @Test
    void acceptsAllExchangeTypes() {
        for (String type : MqConsumeConfig.EXCHANGE_TYPES) {
            MqConsumeConfig config = MqConsumeConfig.from(Map.of(
                    "dataSourceId", 1, "queue", "q", "exchangeType", type));
            assertThatCode(config::validate).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsUnknownExchangeType() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of(
                "dataSourceId", 1, "queue", "q", "exchangeType", "pubsub"));

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exchangeType");
    }

    @Test
    void roundTripsExchangeTypeInMap() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of(
                "dataSourceId", 1, "queue", "q", "exchangeType", "fanout", "routingKey", "rk"));

        assertThat(config.toMap()).containsEntry("exchangeType", "fanout");
    }

    @Test
    void absentExchangeTypeNotWrittenToMap() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q"));

        assertThat(config.toMap()).doesNotContainKey("exchangeType");
    }

    @Test
    void effectiveExchangeFallsBackToDataSourceNameWhenAbsentOrBlank() {
        assertThat(MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q"))
                .effectiveExchange("mes.exchange")).isEqualTo("mes.exchange");
        assertThat(MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q", "exchange", "  "))
                .effectiveExchange("mes.exchange")).isEqualTo("mes.exchange");
        assertThat(MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q", "exchange", "edge.x"))
                .effectiveExchange("mes.exchange")).isEqualTo("edge.x");
    }

    @Test
    void effectiveRoutingKeyFallsBackToQueueWhenAbsentOrBlank() {
        assertThat(MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q"))
                .effectiveRoutingKey("q")).isEqualTo("q");
        assertThat(MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q", "routingKey", "  "))
                .effectiveRoutingKey("q")).isEqualTo("q");
        assertThat(MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q", "routingKey", "order.#"))
                .effectiveRoutingKey("q")).isEqualTo("order.#");
    }
}
