package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 合并动作（#142 / ADR-0017 决策 6 的**修订版**）：攒住走过来的**一批**消息，**打成一条报文**（N → 1）。
 *
 * <p><b>它不认识出口</b>：发去哪儿由链上后面的出站动作决定（{@code HTTP_CALL} / {@code MQ_PUBLISH}）。
 * 现在合并只管把这一批包成 {@code {"<bodyField>": [ …每条消息自己那一片… ]}}，还原由
 * {@link OneToManyConfig} 那一档负责。</p>
 *
 * <p>攒齐的边界仍是**上游读取批读完那一刻**（ADR-0017 决策 5）：不设条数阈值、不设超时——
 * 要限制单次发送条数就调读库那一步的批大小，一处生效。打包结果过容量闸（装不下当场失败、绝不写半截）。</p>
 */
public record AggregateConfig(String bodyField) implements ActionConfig {

    /** 请求体里那个字段名：纯名字段（不认路径与下标）。 */
    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*";

    public static AggregateConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("bodyField"));
        return new AggregateConfig(ConfigValues.str(map.get("bodyField")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.AGGREGATE;
    }

    @Override
    public void validate() {
        if (bodyField == null || bodyField.isBlank()) {
            throw new IllegalArgumentException("合并动作需要 bodyField（整批包成 {\"<bodyField>\": [ … ]} 的字段名）");
        }
        if (!bodyField.matches(NAME)) {
            throw new IllegalArgumentException("bodyField 应为纯字段名（如 codes），不要写路径或数组下标: " + bodyField);
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bodyField", bodyField);
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
