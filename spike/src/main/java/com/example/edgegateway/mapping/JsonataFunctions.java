package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata.JFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** JSONata 自定义函数集合（S0 验证：dashjoin registerFunction + JFunction 签名）。 */
public final class JsonataFunctions {

    /** 解析 JSON 字符串用（ObjectMapper 线程安全，复用一份）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 没写格式串时用的默认值（{@code JsonataRuleCompiler} 与 {@link DatePatterns} 都认这一个）。 */
    private static final String DEFAULT_PATTERN = DatePatterns.DEFAULT;

    private JsonataFunctions() {
    }

    /**
     * {@code $parse}：把一个 JSON 字符串解析成对象或数组。
     *
     * <p>给「一单多行 → 一条报文」这个形状用：源库侧用 JSON 聚合（SQL Server 的 {@code FOR JSON PATH}、
     * MySQL 的 {@code JSON_ARRAYAGG}/{@code JSON_OBJECT}）把明细折成一列字符串，这里把它还原成数组，
     * 再交给 {@code $map} 加工。**引擎自带的函数里没有它**（实测 {@code $parse(...)} 报
     * "Attempted to invoke a non-function"），所以要自己注册。</p>
     *
     * <p>已经是结构（Map/List）时原样返回，方便「这一列有时候是 JSON 类型、有时候是字符串」的存量数据
     * ——签名因此写成 {@code <j:j>}（任意→任意）：写成 {@code <s:j>} 的话，引擎会在调用前就把非字符串参数拦掉，
     * 那条宽容分支根本到不了（实测 {@code Argument "1" of Object "parse" does not match Object signature}）。
     * **解析不出来就抛**——与其它求值错误一样让映射步 FAILED，不静默出空。</p>
     */
    public static JFunction parseJson() {
        return new JFunction((input, args) -> {
            Object value = args.get(0);
            if (value == null) {
                return null;
            }
            if (value instanceof Map || value instanceof List) {
                return value;
            }
            try {
                return MAPPER.readValue(String.valueOf(value), Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("$parse 解析 JSON 失败: " + e.getOriginalMessage(), e);
            }
        }, "<j:j>");
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
