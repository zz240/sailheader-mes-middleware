package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata.JFunction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** JSONata 自定义函数集合（S0 验证：dashjoin registerFunction + JFunction 签名）。 */
public final class JsonataFunctions {

    /** 没写格式串时用的默认值（{@code JsonataRuleCompiler} 与 {@link DatePatterns} 都认这一个）。 */
    private static final String DEFAULT_PATTERN = DatePatterns.DEFAULT;

    private JsonataFunctions() {
    }

    /**
     * DATE 格式化函数：签名 (string, string) → string。
     * 兼容 ISO-8601 时间戳（OffsetDateTime/Instant/LocalDateTime）与纯日期串（LocalDate），
     * 无法解析时原样返回。
     */
    public static JFunction dateFormat() {
        return new JFunction((input, args) -> {
            Object value = args.get(0);
            String pattern = (String) args.get(1);
            return formatDate(value, pattern);
        }, "<ss:s>");
    }

    /**
     * DATE 转换的实际行为（运行时跑的就是这一份）：解析得出来就格式化，解析不出来**原样返回**。
     *
     * <p>开出来是给「校验配置」用的：那条检查要拿同一个实现回答「这个格式串在这份样例值上跑得出来吗」。
     * 另写一套解析链的话，界面上说的和运行时做的就是两件事。</p>
     *
     * @param pattern 格式串；null 时用默认值（与编译器一致）。**格式串本身合不合法另判**
     *                （{@link DatePatterns}）：非法格式串在这里会抛，跟运行时一样。
     */
    public static String formatDate(Object value, String pattern) {
        if (value == null) {
            return null;
        }
        String formatted = formatOrNull(value, pattern);
        return formatted != null ? formatted : String.valueOf(value);
    }

    /**
     * 这个值被这个格式串真格式化出来了没有。
     *
     * <p>{@code false} 就是运行时那条宽容语义：四种输入形状都解析不出来（或解析出来了却格式化不成），
     * 这个值会**原样发出去**，任务照样成功——错只在目标那边看得见。</p>
     */
    public static boolean canFormat(Object value, String pattern) {
        return value != null && formatOrNull(value, pattern) != null;
    }

    /** 四种输入形状各试一次；一次都没成时返回 null（调用方据此区分「格式化了」与「原样透传」）。 */
    private static String formatOrNull(Object value, String pattern) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern == null ? DEFAULT_PATTERN : pattern);
        String raw = String.valueOf(value);
        try {
            return OffsetDateTime.parse(raw).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Instant.parse(raw).atZone(ZoneOffset.UTC).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(raw).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(raw).format(formatter);
        } catch (Exception ignored) {
            return null;
        }
    }
}
