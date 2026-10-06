package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 扇回动作（#142 / ADR-0017 决策 6 的修订版）：把出站拿回来的**响应**按配对字段摊回
 * **原来那 N 条消息**（1 → N）。
 *
 * <p>它和拆分动作（{@link SplitConfig}）形似而神不同，所以是两个动作种类：</p>
 * <ul>
 *   <li>拆分**新造消息**（每条子消息现生成一个消息标识）；扇回**回到原来那几条**——
 *       消息标识、业务键、父消息标识、批内序号都沿用（它们从合并那一步起就记在成员清单里，
 *       见 {@code RouteGenerator} 的 {@code edge.batchManifest} 属性）。</li>
 *   <li>拆分**换批号**（新的一批子消息）；扇回**不换号**——它是把这一批还原回成员，
 *       不是新的一批（ADR-0017 决策 3 的修订）。</li>
 * </ul>
 *
 * <p>配置四格：</p>
 * <ul>
 *   <li><b>来源步骤</b> {@code sourceStep}：响应在哪个动作的输出里（通常就是那个出站动作的序号）。
 *       必填——扇回必须知道去哪儿取回应；</li>
 *   <li><b>来源路径</b> {@code sourcePath}：来源里的点分数组路径，空 = 来源本身就是那片数组；</li>
 *   <li><b>配对字段</b> {@code resultKeyField}：响应每一项里这个字段的值，与消息的**业务键**相等就算配上
 *       （一条消息一个业务键，配对键只能取自它，ADR-0017 决策 9）；</li>
 *   <li><b>结果落 body 的键</b> {@code attachAs}：配对到的那一份写进各自 body 的这个键，
 *       紧随其后的动作直接用。</li>
 * </ul>
 */
public record FanoutConfig(
        Integer sourceStep,
        String sourcePath,
        String resultKeyField,
        String attachAs
) implements ActionConfig {

    /** 点分 JSON 路径：来源路径允许数组下标段（{@code lines.0.items}）。 */
    private static final String PATH = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*";

    /** 两个键都只认纯名字段（不认路径与下标）：它们分别是响应项里的键、body 上的键。 */
    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*";

    public static FanoutConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("sourceStep", "sourcePath", "resultKeyField", "attachAs"));
        Object step = map.get("sourceStep");
        return new FanoutConfig(
                step == null ? null : Integer.valueOf(step.toString()),
                ConfigValues.str(map.get("sourcePath")),
                ConfigValues.str(map.get("resultKeyField")),
                ConfigValues.str(map.get("attachAs")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.FANOUT;
    }

    @Override
    public void validate() {
        if (sourceStep == null) {
            throw new IllegalArgumentException("扇回动作需要 sourceStep（响应在哪个动作的输出里，通常是出站动作的序号）");
        }
        if (sourceStep < 0) {
            throw new IllegalArgumentException("扇回动作的 sourceStep 不能为负: " + sourceStep);
        }
        if (sourcePath != null && !sourcePath.isBlank() && !sourcePath.matches(PATH)) {
            throw new IllegalArgumentException(
                    "扇回来源路径应为点分 JSON 路径（如 codes / data.items），禁止空段/非法字符: " + sourcePath);
        }
        requireName(resultKeyField, "resultKeyField", "响应里用来配对的字段（与消息的业务键相等就算配上）");
        requireName(attachAs, "attachAs", "配对结果塞进各自 body 的哪个键");
    }

    private static void requireName(String value, String field, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("扇回动作需要 " + field + "（" + what + "）");
        }
        if (!value.matches(NAME)) {
            throw new IllegalArgumentException(field + " 应为纯字段名（不要写路径或数组下标）: " + value);
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceStep", sourceStep);
        if (sourcePath != null) {
            map.put("sourcePath", sourcePath);
        }
        map.put("resultKeyField", resultKeyField);
        map.put("attachAs", attachAs);
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
