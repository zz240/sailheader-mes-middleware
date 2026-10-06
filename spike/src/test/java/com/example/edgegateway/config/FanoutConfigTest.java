package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 扇回动作的配置模型（#142）：来源步骤（必填）、可空来源路径、响应配对字段、配对结果落 body 的键。
 *
 * <p>它和拆分动作**不是**一回事，所以配置也不一样：拆分的来源可空（空 = 拆当前消息体），
 * 扇回必须知道去哪个步骤取回应；拆分配的是「元素键」（子消息的业务键取自元素里哪个字段），
 * 扇回配的是「与业务键相等的那个响应字段」。</p>
 */
class FanoutConfigTest {

    private static final Map<String, Object> FULL = Map.of(
            "sourceStep", 2, "sourcePath", "codes", "resultKeyField", "code", "attachAs", "handle");

    @Test
    void parsesAllFourFields() {
        FanoutConfig config = FanoutConfig.from(FULL);

        assertThat(config.type()).isEqualTo(ActionKind.FANOUT);
        assertThat(config.sourceStep()).isEqualTo(2);
        assertThat(config.sourcePath()).isEqualTo("codes");
        assertThat(config.resultKeyField()).isEqualTo("code");
        assertThat(config.attachAs()).isEqualTo("handle");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void sourcePathMayBeAbsentMeaningTheResponseItselfIsTheArray() {
        Map<String, Object> map = new LinkedHashMap<>(FULL);
        map.remove("sourcePath");

        FanoutConfig config = FanoutConfig.from(map);

        assertThat(config.sourcePath()).isNull();
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void sourceStepIsRequired() {
        Map<String, Object> map = new LinkedHashMap<>(FULL);
        map.remove("sourceStep");

        assertThatThrownBy(() -> FanoutConfig.from(map).validate())
                .as("扇回必须知道去哪儿取回应——这与拆分的「空 = 当前消息体」不同")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceStep");
        assertThatThrownBy(() -> FanoutConfig.from(Map.of(
                "sourceStep", -1, "resultKeyField", "code", "attachAs", "handle")).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
    }

    @Test
    void thePairingKeysMustBePlainNames() {
        for (String key : new String[]{"resultKeyField", "attachAs"}) {
            Map<String, Object> map = new LinkedHashMap<>(FULL);
            map.put(key, "data.0.items");
            assertThatThrownBy(() -> FanoutConfig.from(map).validate())
                    .as(key + " 只认纯字段名")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(key);
        }
        Map<String, Object> missing = new LinkedHashMap<>(FULL);
        missing.remove("attachAs");
        assertThatThrownBy(() -> FanoutConfig.from(missing).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attachAs");
    }

    @Test
    void rejectsMalformedSourcePathAndUnknownFields() {
        Map<String, Object> badPath = new LinkedHashMap<>(FULL);
        badPath.put("sourcePath", "lines[0].item");
        assertThatThrownBy(() -> FanoutConfig.from(badPath).validate())
                .as("路径只认点分段，不认方括号下标")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源路径");
        assertThatThrownBy(() -> FanoutConfig.from(Map.of(
                "sourceStep", 2, "resultKeyField", "code", "attachAs", "handle", "elementKey", "code")))
                .as("「元素键」是拆分那一档的配置，扇回没有它")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void roundTripsThroughMap() {
        FanoutConfig original = FanoutConfig.from(FULL);

        assertThat(FanoutConfig.from(new LinkedHashMap<>(original.toMap()))).isEqualTo(original);
    }
}
