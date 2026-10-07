package com.example.edgegateway.config.action;

import com.example.edgegateway.model.ActionKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一分多动作（1 → N，两档）的配置口径。
 *
 * <p>押的是：两档各认各的键、缺什么报什么、{@code toMap()} 只写本档用到的键（落库 JSON 与合并前的两种
 * 一一对应，只多一格 {@code mode}）。</p>
 */
class OneToManyConfigTest {

    private static Map<String, Object> splitMap() {
        Map<String, Object> addition = new LinkedHashMap<>();
        addition.put("mode", "split");
        addition.put("sourceStep", 2);
        addition.put("sourcePath", "lines");
        addition.put("elementKey", "itemCode");
        addition.put("keepParent", true);
        return addition;
    }

    private static Map<String, Object> restoreMap() {
        Map<String, Object> addition = new LinkedHashMap<>();
        addition.put("mode", "restore");
        addition.put("sourceStep", 4);
        addition.put("sourcePath", "results");
        addition.put("resultKeyField", "FCode");
        addition.put("attachAs", "registration");
        return addition;
    }

    /* ── 两档的解析与类型 ─────────────────────────────────────────── */

    @Test
    void splitModeParsesAndKeepsItsOwnKeys() {
        OneToManyConfig config = OneToManyConfig.from(splitMap());

        assertThat(config.type()).isEqualTo(ActionKind.ONE_TO_MANY);
        assertThat(config.mode()).isEqualTo(OneToManyConfig.Mode.SPLIT);
        assertThat(config.isSplit()).isTrue();
        assertThat(config.isRestore()).isFalse();
        assertThat(config.sourceStep()).isEqualTo(2);
        assertThat(config.sourcePath()).isEqualTo("lines");
        assertThat(config.elementKey()).isEqualTo("itemCode");
        assertThat(config.keepParent()).isTrue();
        assertThat(config.resultKeyField()).isNull();
        assertThat(config.attachAs()).isNull();
    }

    @Test
    void restoreModeParsesAndKeepsItsOwnKeys() {
        OneToManyConfig config = OneToManyConfig.from(restoreMap());

        assertThat(config.type()).isEqualTo(ActionKind.ONE_TO_MANY);
        assertThat(config.mode()).isEqualTo(OneToManyConfig.Mode.RESTORE);
        assertThat(config.isRestore()).isTrue();
        assertThat(config.sourceStep()).isEqualTo(4);
        assertThat(config.sourcePath()).isEqualTo("results");
        assertThat(config.resultKeyField()).isEqualTo("FCode");
        assertThat(config.attachAs()).isEqualTo("registration");
        assertThat(config.elementKey()).isNull();
    }

    @Test
    void splitModeOnlyNeedsTheModeAndTheElementKey() {
        OneToManyConfig config = OneToManyConfig.from(Map.of("mode", "split", "elementKey", "itemCode"));

        assertThat(config.sourceStep()).isNull();
        assertThat(config.sourcePath()).isNull();
        assertThat(config.keepParent()).as("缺省摊平外层字段——头行+子行那条形态最省事的那一档").isTrue();
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void modeIsRequiredAndMustBeOneOfTheTwo() {
        assertThatThrownBy(() -> OneToManyConfig.from(Map.of("elementKey", "itemCode")))
                .hasMessageContaining("需要 mode");
        assertThatThrownBy(() -> OneToManyConfig.from(Map.of("mode", "merge", "elementKey", "itemCode")))
                .hasMessageContaining("只能是 split 或 restore");
        assertThat(OneToManyConfig.from(Map.of("mode", " RESTORE ", "sourceStep", 1,
                "resultKeyField", "code", "attachAs", "handle")).mode())
                .as("大小写与空白都认")
                .isEqualTo(OneToManyConfig.Mode.RESTORE);
    }

    /* ── 两档各认各的键：别档的键是「未知字段」，不静默忽略 ───────────── */

    @Test
    void eachModeRejectsTheOtherModesKeys() {
        assertThatThrownBy(() -> OneToManyConfig.from(Map.of("mode", "split", "elementKey", "itemCode",
                "resultKeyField", "code")))
                .hasMessageContaining("未知字段")
                .hasMessageContaining("resultKeyField");
        assertThatThrownBy(() -> OneToManyConfig.from(Map.of("mode", "restore", "sourceStep", 1,
                "resultKeyField", "code", "attachAs", "handle", "elementKey", "itemCode")))
                .hasMessageContaining("未知字段")
                .hasMessageContaining("elementKey");
    }

    @Test
    void typoIsReportedAsAnUnknownKey() {
        Map<String, Object> addition = splitMap();
        addition.put("sorucePath", "lines");

        assertThatThrownBy(() -> OneToManyConfig.from(addition)).hasMessageContaining("未知字段");
    }

    /* ── 必填与取值边界（按档） ──────────────────────────────────── */

    @Test
    void splitModeRequiresAPlainElementKey() {
        assertThatThrownBy(() -> OneToManyConfig.from(Map.of("mode", "split")).validate())
                .hasMessageContaining("elementKey");
        assertThatThrownBy(() -> OneToManyConfig
                .from(Map.of("mode", "split", "elementKey", "lines.0.code")).validate())
                .as("元素键不认数组下标：放开会让配置校验通过、运行期静默取不到值")
                .hasMessageContaining("不要写数组下标");
        assertThatCode(() -> OneToManyConfig
                .from(Map.of("mode", "split", "elementKey", "material.code")).validate())
                .as("点分字段名认")
                .doesNotThrowAnyException();
    }

    @Test
    void restoreModeRequiresSourceStepAndThePairingKeys() {
        assertThatThrownBy(() -> OneToManyConfig.from(Map.of("mode", "restore", "resultKeyField", "code",
                "attachAs", "handle")).validate())
                .hasMessageContaining("sourceStep");
        assertThatThrownBy(() -> OneToManyConfig
                .from(Map.of("mode", "restore", "sourceStep", 1, "attachAs", "handle")).validate())
                .hasMessageContaining("resultKeyField");
        assertThatThrownBy(() -> OneToManyConfig
                .from(Map.of("mode", "restore", "sourceStep", 1, "resultKeyField", "code")).validate())
                .hasMessageContaining("attachAs");
        assertThatThrownBy(() -> OneToManyConfig.from(Map.of("mode", "restore", "sourceStep", 1,
                "resultKeyField", "codes[0].FCode", "attachAs", "handle")).validate())
                .hasMessageContaining("不要写路径或数组下标");
    }

    @Test
    void sourcePathAndSourceStepEdgesAreChecked() {
        assertThatThrownBy(() -> OneToManyConfig
                .from(Map.of("mode", "split", "sourceStep", -1, "elementKey", "itemCode")).validate())
                .hasMessageContaining("不能为负");
        assertThatThrownBy(() -> OneToManyConfig
                .from(Map.of("mode", "split", "sourcePath", "a..b", "elementKey", "itemCode")).validate())
                .hasMessageContaining("点分 JSON 路径");
        assertThatCode(() -> OneToManyConfig
                .from(Map.of("mode", "restore", "sourceStep", 1, "sourcePath", "", "resultKeyField", "code",
                        "attachAs", "handle")).validate())
                .as("空路径 = 来源本身就是那片数组，合法")
                .doesNotThrowAnyException();
    }

    /* ── 落库形状：只写本档用到的键 ───────────────────────────────── */

    @Test
    void toMapWritesOnlyThisModesKeysAndSurvivesARoundTrip() {
        OneToManyConfig split = OneToManyConfig.from(splitMap());
        Map<String, Object> written = split.toMap();

        assertThat(written).containsEntry("mode", "split").containsKeys("sourceStep", "sourcePath", "elementKey",
                "keepParent");
        assertThat(written).doesNotContainKeys("resultKeyField", "attachAs");
        assertThat(OneToManyConfig.from(new LinkedHashMap<>(written))).isEqualTo(split);

        OneToManyConfig restore = OneToManyConfig.from(restoreMap());
        Map<String, Object> writtenRestore = restore.toMap();

        assertThat(writtenRestore).containsEntry("mode", "restore").containsKeys("sourceStep", "sourcePath",
                "resultKeyField", "attachAs");
        assertThat(writtenRestore).doesNotContainKeys("elementKey", "keepParent");
        assertThat(OneToManyConfig.from(new LinkedHashMap<>(writtenRestore))).isEqualTo(restore);
    }

    @Test
    void keepParentFalseIsWrittenAndReadBack() {
        OneToManyConfig config = OneToManyConfig.from(Map.of("mode", "split", "elementKey", "FCode",
                "keepParent", false));

        assertThat(config.keepParent()).isFalse();
        assertThat(config.toMap()).containsEntry("keepParent", false);
        assertThat(OneToManyConfig.from(config.toMap()).keepParent()).isFalse();
    }
}
