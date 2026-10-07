package com.example.edgegateway.config.action;

import com.example.edgegateway.model.ActionKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合并动作的配置模型（#142 / ADR-0017 决策 6 的修订版）：**只有一格**——整批包成哪个字段。
 *
 * <p>出口（数据源 / 路径 / 方法）不再在这儿：早先那六格是被推翻的抉择，它把出口的种类与数量钉死在
 * 合并动作上。所以这里也钉一条反向断言：**写进旧字段就是未知字段**，配错时当场报出来。</p>
 */
class AggregateConfigTest {

    @Test
    void parsesTheOnlyField() {
        AggregateConfig config = AggregateConfig.from(Map.of("bodyField", "codes"));

        assertThat(config.type()).isEqualTo(ActionKind.AGGREGATE);
        assertThat(config.bodyField()).isEqualTo("codes");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void bodyFieldIsRequiredAndMustBeAPlainName() {
        assertThatThrownBy(() -> AggregateConfig.from(Map.of()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bodyField");
        assertThatThrownBy(() -> AggregateConfig.from(Map.of("bodyField", "data.0.items")).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("纯字段名");
    }

    @Test
    void theOldSixFieldShapeIsNowAnUnknownField() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bodyField", "codes");
        map.put("dataSourceId", 2L);

        assertThatThrownBy(() -> AggregateConfig.from(map))
                .as("出口归链上的出站动作：合并动作里出现数据源就是配错了")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void roundTripsThroughMap() {
        AggregateConfig original = AggregateConfig.from(Map.of("bodyField", "items"));

        assertThat(AggregateConfig.from(new LinkedHashMap<>(original.toMap()))).isEqualTo(original);
    }
}
