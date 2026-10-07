package com.example.edgegateway.config.action;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一分多动作（1 → N）：一个动作、两档配置——拆分档造 N 条新消息，还原档把出站响应还回原来那几条消息。
 *
 * <p>两档的外形是同一个骨架——取来源里的一片数组 → 逐条产生一条消息 → 每条记一行消息步；
 * 差别只在两处：</p>
 * <ul>
 *   <li>{@link Mode#SPLIT} <b>拆分档</b>：造**新**消息——消息标识现生成、业务键取自元素里的
 *       {@code elementKey}、批号换新、父消息标识指向被拆的那条。来源是自己 body 或
 *       {@code context[sourceStep]} 里的一片数组，元素**按顺序**逐条摊开（不需要配对）。</li>
 *   <li>{@link Mode#RESTORE} <b>还原档</b>：把出站拿回来的响应按 {@code resultKeyField} 与**业务键**
 *       配对，**还回原来那 N 条消息**（消息标识 / 业务键 / 父消息标识 / 批内序号 / 批号全部沿用、
 *       不换号），配对到的那一项挂在各自 body 的 {@code attachAs} 键上。它要求上游链上有**合并**
 *       动作（成员清单 {@code edge.batchManifest} 是它与出站之间唯一的联系）。</li>
 * </ul>
 *
 * <p><b>两档的配置键各归各的</b>：公共的只有 {@code mode} / {@code sourceStep} / {@code sourcePath}；
 * 拆分档认 {@code elementKey} / {@code keepParent}，还原档认 {@code resultKeyField} / {@code attachAs}
 * ——写库只写本档那几个键（{@link #toMap()}），所以落库的 JSON 只带本档的键 + 一格
 * {@code mode}。反过来，认到别档的键会直接报「未知字段」，不会静默忽略。</p>
 *
 * <p>两档的前提不同，所以校验也分档：还原档 {@code sourceStep} 必填（它必须知道去哪儿取回应），
 * 拆分档可空（空 = 拆当前消息体）；还原档还必须在链上有上游合并动作（配置期由
 * {@code ChainConsistencyValidator} 拦下来）。</p>
 */
public record OneToManyConfig(
        Mode mode,
        Integer sourceStep,
        String sourcePath,
        /** 拆分档：子消息的业务键取自元素里的哪个字段（必填）；还原档为 {@code null}。 */
        String elementKey,
        /** 拆分档：父消息的外层字段是否摊平进子消息（缺省 {@code true}）；还原档不适用。 */
        boolean keepParent,
        /** 还原档：响应里用来配对的字段（与消息的业务键相等就算配上）；拆分档为 {@code null}。 */
        String resultKeyField,
        /** 还原档：配对结果塞进各自 body 的哪个键；拆分档为 {@code null}。 */
        String attachAs
) implements ActionConfig {

    /** 两档：造新消息（split）/ 还原成员（restore）。JSON 里写小写。 */
    public enum Mode {
        SPLIT,
        RESTORE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode of(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "split" -> SPLIT;
                case "restore" -> RESTORE;
                case "" -> throw new IllegalArgumentException(
                        "一分多动作需要 mode（split = 拆分：造新消息；restore = 还原：还原成员）");
                default -> throw new IllegalArgumentException(
                        "一分多动作的 mode 只能是 split 或 restore: " + raw);
            };
        }
    }

    /** 点分 JSON 路径：来源路径允许数组下标段（{@code lines.0.items}）。 */
    private static final String PATH = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*";

    /**
     * 两个键都只认**纯名字段**：拆分档的 {@code elementKey} 与 {@code ElementMessageFactory.valueAt}
     * 的路径遍历共用一份实现，但那里对数字段有 List 下标这层额外含义，而业务键只该是「元素里叫这个名字
     * 的字段」；还原档的 {@code resultKeyField} / {@code attachAs} 分别是响应项里的键与 body 上的键。
     * 放开下标会让 {@code lines.0.code} 通过配置校验、却在运行期静默取不到值。
     */
    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*";

    public static OneToManyConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        Mode mode = Mode.of(ConfigValues.str(map.get("mode")));
        rejectUnknownKeys(map, mode == Mode.SPLIT
                ? Set.of("mode", "sourceStep", "sourcePath", "elementKey", "keepParent")
                : Set.of("mode", "sourceStep", "sourcePath", "resultKeyField", "attachAs"));
        Object step = map.get("sourceStep");
        return new OneToManyConfig(
                mode,
                step == null ? null : Integer.valueOf(step.toString()),
                ConfigValues.str(map.get("sourcePath")),
                ConfigValues.str(map.get("elementKey")),
                // 缺省 true：外层字段摊平是「头行 + 子行」这条形态要的那一档，缺省给最省事的那个
                map.get("keepParent") == null || Boolean.parseBoolean(map.get("keepParent").toString()),
                ConfigValues.str(map.get("resultKeyField")),
                ConfigValues.str(map.get("attachAs")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.ONE_TO_MANY;
    }

    public boolean isSplit() {
        return mode == Mode.SPLIT;
    }

    public boolean isRestore() {
        return mode == Mode.RESTORE;
    }

    @Override
    public void validate() {
        if (sourceStep != null && sourceStep < 0) {
            throw new IllegalArgumentException("一分多动作的 sourceStep 不能为负: " + sourceStep);
        }
        if (sourcePath != null && !sourcePath.isBlank() && !sourcePath.matches(PATH)) {
            throw new IllegalArgumentException(
                    "来源路径应为点分 JSON 路径（如 lines / data.items / results），禁止空段/非法字符: " + sourcePath);
        }
        if (mode == Mode.SPLIT) {
            if (elementKey == null || elementKey.isBlank()) {
                throw new IllegalArgumentException("拆分档需要 elementKey（子消息的业务键取自元素里的哪个字段）");
            }
            if (!elementKey.matches(NAME)) {
                throw new IllegalArgumentException(
                        "元素键应为点分字段名（如 itemCode / material.code），不要写数组下标: " + elementKey);
            }
            return;
        }
        if (sourceStep == null) {
            throw new IllegalArgumentException(
                    "还原档需要 sourceStep（响应在哪个动作的输出里，通常是那个出站动作的序号）");
        }
        requireName(resultKeyField, "resultKeyField", "响应里用来配对的字段（与消息的业务键相等就算配上）");
        requireName(attachAs, "attachAs", "配对结果塞进各自 body 的哪个键");
    }

    private static void requireName(String value, String field, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("还原档需要 " + field + "（" + what + "）");
        }
        if (!value.matches(NAME)) {
            throw new IllegalArgumentException(field + " 应为纯字段名（不要写路径或数组下标）: " + value);
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", mode.wire());
        if (sourceStep != null) {
            map.put("sourceStep", sourceStep);
        }
        if (sourcePath != null) {
            map.put("sourcePath", sourcePath);
        }
        if (mode == Mode.SPLIT) {
            if (elementKey != null) {
                map.put("elementKey", elementKey);
            }
            map.put("keepParent", keepParent);
            return map;
        }
        if (resultKeyField != null) {
            map.put("resultKeyField", resultKeyField);
        }
        if (attachAs != null) {
            map.put("attachAs", attachAs);
        }
        return map;
    }

    private static void rejectUnknownKeys(Map<String, Object> map, Set<String> allowed) {
        map.keySet().stream()
                .filter(k -> !allowed.contains(k))
                .findFirst()
                .ifPresent(k -> {
                    throw new IllegalArgumentException("未知字段: " + k);
                });
    }
}
