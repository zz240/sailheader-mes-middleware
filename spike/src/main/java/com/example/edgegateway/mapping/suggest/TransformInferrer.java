package com.example.edgegateway.mapping.suggest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 样例值转换推断：取前 N 行原始样例（含重复），按确定性规则判类型。
 *
 * <p>判定顺序：全布尔 → 全整数 → 全数字 → 全日期（记录 pattern）→
 * 去重数在 [2,20] 且少于行数一半（封闭枚举）→ 全相同（常量）→ 字符串。</p>
 */
public final class TransformInferrer {

    /** 推断结果：类型 + 可选参数（日期格式/常量值）。 */
    public record Inference(TransformKind kind, String datePattern, String constant) {

        public static Inference of(TransformKind kind) {
            return new Inference(kind, null, null);
        }
    }

    private record DatePattern(String name, Pattern regex) {
    }

    private static final List<DatePattern> DATE_PATTERNS = List.of(
            new DatePattern("yyyy-MM-dd HH:mm:ss",
                    Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}$")),
            new DatePattern("yyyy-MM-dd",
                    Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$")),
            new DatePattern("yyyy/MM/dd",
                    Pattern.compile("^\\d{4}/\\d{2}/\\d{2}$")),
            new DatePattern("ISO-8601",
                    Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$"))
    );

    /** 枚举判定上限：去重取值数不能超过总行数的一半（编码列/名称列不判枚举）。 */
    private static final int MAX_ENUM_DISTINCT = 20;

    private TransformInferrer() {
    }

    public static Inference infer(List<Object> samples) {
        List<Object> values = samples == null ? List.of() : samples.stream()
                .filter(v -> v != null)
                .toList();
        if (values.isEmpty()) {
            return Inference.of(TransformKind.STRING);
        }
        if (values.stream().allMatch(v -> v instanceof Boolean)) {
            return Inference.of(TransformKind.BOOLEAN);
        }
        if (values.stream().allMatch(v -> v instanceof Integer || v instanceof Long || v instanceof Short)) {
            return Inference.of(TransformKind.INT);
        }
        if (values.stream().allMatch(v -> v instanceof Number)) {
            return Inference.of(TransformKind.NUMBER);
        }
        for (DatePattern dp : DATE_PATTERNS) {
            boolean allMatch = values.stream()
                    .allMatch(v -> v instanceof String s && dp.regex().matcher(s).matches());
            if (allMatch) {
                return new Inference(TransformKind.DATE, dp.name(), null);
            }
        }
        Set<String> distinct = new LinkedHashSet<>();
        values.forEach(v -> distinct.add(String.valueOf(v)));
        if (distinct.size() >= 2
                && distinct.size() <= MAX_ENUM_DISTINCT
                && distinct.size() < values.size() * 0.5) {
            return Inference.of(TransformKind.ENUM);
        }
        if (distinct.size() == 1) {
            return new Inference(TransformKind.CONSTANT, null, distinct.iterator().next());
        }
        return Inference.of(TransformKind.STRING);
    }
}
