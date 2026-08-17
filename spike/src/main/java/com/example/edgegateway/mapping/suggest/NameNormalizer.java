package com.example.edgegateway.mapping.suggest;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 字段名归一化：小写 → 去分隔符/数字 → camelCase 拆词 → 别名词典归一。 */
public final class NameNormalizer {

    /** 归一化结果：紧凑串用于相似度，token 集合用于精确匹配。 */
    public record Normalized(String compact, Set<String> tokens) {
    }

    /** 常见缩写/同义词，映射到同一规范词（对应 #11 别名词典）。 */
    private static final Map<String, String> ALIASES = Map.of(
            "qty", "quantity",
            "no", "number",
            "dt", "date",
            "amt", "amount",
            "vendor", "supplier"
    );

    private static final Pattern CAMEL_SPLIT = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    private NameNormalizer() {
    }

    public static Normalized normalize(String name) {
        String lower = name.toLowerCase();
        String withSpaces = CAMEL_SPLIT.matcher(lower).replaceAll("$1 $2");
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : TOKEN_SPLIT.split(withSpaces)) {
            if (!raw.isEmpty()) {
                tokens.add(ALIASES.getOrDefault(raw, raw));
            }
        }
        return new Normalized(String.join("", tokens), Set.copyOf(tokens));
    }
}
