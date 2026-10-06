package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 拆分动作的配置模型（#140）：来源（步骤 + 路径）、元素键、是否保留外层字段。
 *
 * <p>口径的三条要点：{@code sourceStep} 可空（空 = 拆当前消息体）、{@code sourcePath} 可空
 * （空 = 来源本身那个数组就是答案）、{@code elementKey} 必填（子消息的业务键得有出处）。</p>
 */
class SplitConfigTest {

    @Test
    void parsesAllThreeFields() {
        SplitConfig config = SplitConfig.from(Map.of(
                "sourceStep", 2, "sourcePath", "data.items", "elementKey", "materialCode", "keepParent", false));

        assertThat(config.type()).isEqualTo(ActionKind.SPLIT);
        assertThat(config.sourceStep()).isEqualTo(2);
        assertThat(config.sourcePath()).isEqualTo("data.items");
        assertThat(config.elementKey()).isEqualTo("materialCode");
        assertThat(config.keepParent()).isFalse();
    }

    @Test
    void sourceStepAndPathMayBeAbsentMeaningSplitTheCurrentBody() {
        SplitConfig config = SplitConfig.from(Map.of("elementKey", "itemCode"));

        assertThat(config.sourceStep())
                .as("空 = 拆**当前消息体**（不是任务输入）；这是票面那句「来源：消息体 或 来源步骤 + 路径」的前一半")
                .isNull();
        assertThat(config.sourcePath())
                .as("空路径 = 来源本身就得是那个数组")
                .isNull();
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void keepParentDefaultsToTrue() {
        assertThat(SplitConfig.from(Map.of("elementKey", "itemCode")).keepParent())
                .as("缺省摊平外层字段：头行 + 子行那条形态最常用")
                .isTrue();
    }

    @Test
    void elementKeyIsRequired() {
        SplitConfig config = SplitConfig.from(Map.of("sourcePath", "lines"));

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elementKey");
    }

    @Test
    void rejectsMalformedPaths() {
        assertThatThrownBy(() -> SplitConfig.from(Map.of("elementKey", "a..b")).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("元素键");
        assertThatThrownBy(() -> SplitConfig.from(Map.of(
                "elementKey", "itemCode", "sourcePath", "lines[0].item")).validate())
                .as("路径只认点分段，不认方括号下标")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源路径");
        // 来源路径可以带下标段（lines.0.items），但**元素键不行**：业务键只该是"元素里叫这个名字的字段"，
        // 放开下标会让它在运行期静默取不到值
        assertThatCode(() -> SplitConfig.from(Map.of(
                "elementKey", "itemCode", "sourcePath", "data.0.lines")).validate())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SplitConfig.from(Map.of("elementKey", "lines.0.code")).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不要写数组下标");
    }

    @Test
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> SplitConfig.from(Map.of("elementKey", "a", "sorucePath", "lines")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void roundTripsThroughMap() {
        SplitConfig original = SplitConfig.from(Map.of(
                "sourceStep", 3, "sourcePath", "lines", "elementKey", "itemCode", "keepParent", true));
        Map<String, Object> map = new LinkedHashMap<>(original.toMap());

        SplitConfig again = SplitConfig.from(map);

        assertThat(again).isEqualTo(original);
        assertThat(map)
                .as("keepParent 恒写出来（缺省也是 true）：这是「存了什么就按什么跑」的那一格")
                .containsEntry("keepParent", true);
    }
}
