package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合并动作的配置模型（#142）：六格字段、方法缺省与边界、三个键得是纯字段名。
 *
 * <p>刻意**没有**的配置也在这儿钉一下：没有条数阈值、没有超时——攒齐的边界是上游读取批读完那一刻
 * （ADR-0017 决策 3），所以未知字段里出现它们就是配置写错了。</p>
 */
class AggregateConfigTest {

    private static final Map<String, Object> FULL = Map.of(
            "dataSourceId", 2L, "path", "/register", "method", "put",
            "bodyField", "items", "resultKeyField", "code", "attachAs", "handle");

    @Test
    void parsesAllSixFields() {
        AggregateConfig config = AggregateConfig.from(FULL);

        assertThat(config.type()).isEqualTo(ActionKind.AGGREGATE);
        assertThat(config.dataSourceId()).isEqualTo(2L);
        assertThat(config.path()).isEqualTo("/register");
        assertThat(config.method()).as("方法归一成大写").isEqualTo("PUT");
        assertThat(config.bodyField()).isEqualTo("items");
        assertThat(config.resultKeyField()).isEqualTo("code");
        assertThat(config.attachAs()).isEqualTo("handle");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void methodDefaultsToPost() {
        Map<String, Object> map = new LinkedHashMap<>(FULL);
        map.remove("method");

        assertThat(AggregateConfig.from(map).method())
                .as("合并出去的是报文，缺省给 POST")
                .isEqualTo("POST");
    }

    @Test
    void rejectsMethodsWithoutABody() {
        Map<String, Object> map = new LinkedHashMap<>(FULL);
        map.put("method", "get");

        assertThatThrownBy(() -> AggregateConfig.from(map).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("带 body");
    }

    @Test
    void requiresEveryConfiguredKey() {
        for (String missing : new String[]{"dataSourceId", "path", "bodyField", "resultKeyField", "attachAs"}) {
            Map<String, Object> map = new LinkedHashMap<>(FULL);
            map.remove(missing);
            assertThatThrownBy(() -> AggregateConfig.from(map).validate())
                    .as("少了 " + missing)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(missing);
        }
    }

    @Test
    void rejectsPathsOrSubscriptsInTheThreeKeys() {
        for (String key : new String[]{"bodyField", "resultKeyField", "attachAs"}) {
            Map<String, Object> map = new LinkedHashMap<>(FULL);
            map.put(key, "data.0.items");
            assertThatThrownBy(() -> AggregateConfig.from(map).validate())
                    .as(key + " 只认纯字段名")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(key);
        }
    }

    @Test
    void rejectsUnknownFields() {
        Map<String, Object> map = new LinkedHashMap<>(FULL);
        map.put("timeoutSeconds", 5);

        assertThatThrownBy(() -> AggregateConfig.from(map))
                .as("超时只当失败判据、不当发送触发：MVP 没有这一格，写了就是配错")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void roundTripsThroughMap() {
        AggregateConfig original = AggregateConfig.from(FULL);
        Map<String, Object> map = new LinkedHashMap<>(original.toMap());

        assertThat(AggregateConfig.from(map)).isEqualTo(original);
        assertThat(map).containsEntry("method", "PUT").containsEntry("bodyField", "items");
    }
}
