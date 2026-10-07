package com.example.edgegateway.config.action;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #72：源动作 businessKey 配置校验——READ_SQL 为列名、MQ_CONSUME 为点分 JSON 路径，可空。 */
class BusinessKeyConfigValidationTest {

    @Test
    void readSqlRejectsDotContainingBusinessKey() {
        ReadSqlConfig config = ReadSqlConfig.from(Map.of(
                "dataSourceId", 1, "sql", "select 1", "businessKey", "order.no"));
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务键");
    }

    @Test
    void readSqlAcceptsSimpleColumnName() {
        ReadSqlConfig config = ReadSqlConfig.from(Map.of(
                "dataSourceId", 1, "sql", "select 1", "businessKey", "order_no"));
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void consumeRejectsEmptyPathSegment() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of(
                "dataSourceId", 1, "queue", "q", "businessKey", "a..b"));
        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务键");
    }

    @Test
    void consumeAcceptsDotPath() {
        MqConsumeConfig config = MqConsumeConfig.from(Map.of(
                "dataSourceId", 1, "queue", "q", "businessKey", "data.orderNo"));
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void businessKeyIsOptional() {
        ReadSqlConfig read = ReadSqlConfig.from(Map.of("dataSourceId", 1, "sql", "select 1"));
        MqConsumeConfig consume = MqConsumeConfig.from(Map.of("dataSourceId", 1, "queue", "q"));
        assertThatCode(read::validate).doesNotThrowAnyException();
        assertThatCode(consume::validate).doesNotThrowAnyException();
    }
}
