package com.example.edgegateway.config.action;

import com.example.edgegateway.model.ActionKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP 调用动作的配置模型（#148 新增成功条件两格）：数据源 + 路径 + 方法 + body 来源步骤，
 * 外加 {@code successPath} / {@code successExpect}。
 *
 * <p>「只写非空字段」是 {@code toMap()} 既有口径，前端表单清空后保存的 {@code addition} 里
 * 因此不会出现这两格——这条由 {@link #blankSuccessFieldsAreOmittedFromMap()} 钉住。</p>
 *
 * <p>响应超时 {@code timeoutMs}（毫秒，可选）照同一条口径：缺省 {@code null}、不落库；写 0 或负数在
 * 配置期就被拒——HttpClient 的 {@code Timeout} 里 0 表示"无限"，写成 0 等于把刚加的保护关掉；
 * 判断要先挡 {@code null}（{@code Integer} 直接和 0 比会拆箱 NPE）。它怎么并进端点 URI 不在这层，
 * 见 {@code service/ReadWriteFragments#withTimeout}。</p>
 */
class HttpCallConfigTest {

    @Test
    void parsesSuccessConditionFields() {
        HttpCallConfig config = HttpCallConfig.from(Map.of(
                "dataSourceId", 2L, "path", "/id-register", "method", "POST",
                "successPath", "code", "successExpect", "E001"));

        assertThat(config.type()).isEqualTo(ActionKind.HTTP_CALL);
        assertThat(config.successPath()).isEqualTo("code");
        assertThat(config.successExpect()).isEqualTo("E001");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void existingShapeStillWorksWithoutSuccessCondition() {
        HttpCallConfig config = HttpCallConfig.from(Map.of("dataSourceId", 2L, "path", "/register"));

        assertThat(config.successPath()).isNull();
        assertThat(config.successExpect()).isNull();
        assertThatCode(config::validate).doesNotThrowAnyException();
        assertThat(config.toMap()).as("不配成功条件时 toMap 不多出键（存量配置零影响）")
                .containsOnlyKeys("dataSourceId", "path");
    }

    @Test
    void expectWithoutPathIsRejectedAtConfigTime() {
        assertThatThrownBy(() -> HttpCallConfig.from(
                Map.of("dataSourceId", 2L, "successExpect", "E001")).validate())
                .as("成功条件需要 successPath：光有期望值不知道该看响应体的哪里")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successPath");
    }

    @Test
    void pathWithoutExpectIsAValidExistenceOnlyCondition() {
        assertThatCode(() -> HttpCallConfig.from(
                Map.of("dataSourceId", 2L, "successPath", "handle")).validate())
                .as("只写路径 = 该字段必须存在，是合法配置")
                .doesNotThrowAnyException();
    }

    @Test
    void unknownKeysAreStillRejected() {
        assertThatThrownBy(() -> HttpCallConfig.from(
                Map.of("dataSourceId", 2L, "successFields", "code")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void roundTripsThroughMap() {
        HttpCallConfig original = HttpCallConfig.from(Map.of(
                "dataSourceId", 2L, "path", "/register", "method", "POST",
                "bodySourceStep", 2, "successPath", "data.status", "successExpect", "OK",
                "timeoutMs", 1500));

        assertThat(original.toMap()).as("超时也要落库，否则保存后重开就丢").containsEntry("timeoutMs", 1500);
        assertThat(HttpCallConfig.from(new LinkedHashMap<>(original.toMap()))).isEqualTo(original);
    }

    @Test
    void blankSuccessFieldsAreOmittedFromMap() {
        HttpCallConfig config = HttpCallConfig.from(Map.of(
                "dataSourceId", 2L, "path", "/register", "successPath", "", "successExpect", ""));

        assertThat(config.toMap()).as("前端清空两格后保存的 addition 里不该出现它们")
                .doesNotContainKeys("successPath", "successExpect");
    }

    @Test
    void timeoutIsOptionalAndAbsentByDefault() {
        HttpCallConfig config = HttpCallConfig.from(Map.of("dataSourceId", 2L, "path", "/register"));

        assertThat(config.timeoutMs()).isNull();
        assertThat(config.toMap())
                .as("缺省的超时不该在 addition 里留一个键（用它就不会覆盖组件默认）")
                .doesNotContainKey("timeoutMs");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void zeroAndNegativeTimeoutAreRejected() {
        assertThatThrownBy(() -> HttpCallConfig.from(Map.of("dataSourceId", 2L, "timeoutMs", 0)).validate())
                .as("Timeout 的 0 是「无限」——写成 0 等于把保护关掉，必须拒")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
        assertThatThrownBy(() -> HttpCallConfig.from(Map.of("dataSourceId", 2L, "timeoutMs", -1)).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMs");
    }
}
