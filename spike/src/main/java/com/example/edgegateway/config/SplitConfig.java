package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 拆分动作（#140 / ADR-0017 的 R2）：一条消息里的一个数组摊成 N 条子消息。
 *
 * <p>配置三格，对应票面那三个字段：</p>
 * <ul>
 *   <li><b>来源</b>：{@code sourceStep} 空 = 取**当前消息体**（上一步刚产出的那条消息）；
 *       填了则从 {@code context[sourceStep]} 取（链上更早动作的输出）。{@code sourcePath} 是来源里的
 *       点分 JSON 路径，**空 = 整个来源就是那个数组**；</li>
 *   <li><b>元素键</b>：{@code elementKey}——每条子消息的业务键取自元素里的哪个字段（必填：
 *       头行与子行不是同一个层级的实体，这条由拆分自己拍板，见 ADR-0017 决策 9）；</li>
 *   <li><b>是否保留外层字段</b>：{@code keepParent}——父消息的外层字段是否摊平进子消息
 *       （缺省 {@code true}）。它只影响子消息 body 里有什么，**不影响谱系**：父消息标识照记。</li>
 * </ul>
 *
 * <p>拆出来的每条子消息五样齐全（消息标识、业务键、批内序号、父消息标识、批号），
 * 由 {@link com.example.edgegateway.service.ElementMessageFactory} 的 split 那一档初始化；
 * 批号是**一次拆分一个**、现生成（ADR-0017 决策 3）。</p>
 */
public record SplitConfig(
        Integer sourceStep,
        String sourcePath,
        String elementKey,
        boolean keepParent
) implements ActionConfig {

    /** 点分 JSON 路径：来源路径允许数组下标段（{@code lines.0.items}）。 */
    private static final String PATH = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*";
    /**
     * 元素键只认**纯名字段**（不含下标段）：它与 {@code ElementMessageFactory.valueAt} 的路径遍历共用一份
     * 实现，但那里对数字段有 List 下标这层额外含义，而业务键只该是「元素里叫这个名字的字段」。
     * 放开下标会让 {@code lines.0.code} 通过配置校验、却在运行期静默取不到值。
     */
    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*";

    public static SplitConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("sourceStep", "sourcePath", "elementKey", "keepParent"));
        Object step = map.get("sourceStep");
        return new SplitConfig(
                step == null ? null : Integer.valueOf(step.toString()),
                ConfigValues.str(map.get("sourcePath")),
                ConfigValues.str(map.get("elementKey")),
                // 缺省 true：外层字段摊平是「头行 + 子行」这条形态要的那一档，缺省给最省事的那个
                map.get("keepParent") == null || Boolean.parseBoolean(map.get("keepParent").toString()));
    }

    @Override
    public ActionKind type() {
        return ActionKind.SPLIT;
    }

    @Override
    public void validate() {
        if (sourceStep != null && sourceStep < 0) {
            throw new IllegalArgumentException("拆分动作的 sourceStep 不能为负: " + sourceStep);
        }
        if (sourcePath != null && !sourcePath.isBlank() && !sourcePath.matches(PATH)) {
            throw new IllegalArgumentException(
                    "拆分来源路径应为点分 JSON 路径（如 lines / data.items），禁止空段/非法字符: " + sourcePath);
        }
        if (elementKey == null || elementKey.isBlank()) {
            throw new IllegalArgumentException("拆分动作需要 elementKey（子消息的业务键取自元素里的哪个字段）");
        }
        if (!elementKey.matches(NAME)) {
            throw new IllegalArgumentException(
                    "元素键应为点分字段名（如 itemCode / material.code），不要写数组下标: " + elementKey);
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (sourceStep != null) {
            map.put("sourceStep", sourceStep);
        }
        if (sourcePath != null) {
            map.put("sourcePath", sourcePath);
        }
        if (elementKey != null) {
            map.put("elementKey", elementKey);
        }
        map.put("keepParent", keepParent);
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
