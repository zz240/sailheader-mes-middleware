package com.example.edgegateway.mapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 日期格式串（{@code datePattern}）的合法性（#111 / ADR-0012 决策 2）。
 *
 * <p>为什么要有这一层：{@link JsonataFunctions#formatDate} 的 {@code DateTimeFormatter.ofPattern}
 * 写在所有 try 之外——非法格式串在运行时不是「解析不出来就原样透传」，而是**直接抛**，那条转换一跑到
 * 任务就失败。而结构校验、编译、草稿校验三层以前一层都不查它（ADR-0012 背景第五条）。</p>
 *
 * <p>「合法」是两件事：</p>
 * <ol>
 *   <li>平台解析器认这个串（{@code ofPattern}）；</li>
 *   <li>串里没有「长得像、含义却不是」的陷阱字母：{@code Y} 是「周所在年」（不是年，{@code y} 才是）、
 *       {@code D} 是「一年中的第几天」（不是日，{@code d} 才是）。这两个能过解析器，算出来却是
 *       {@code 2026-08-233} 这种值——同样是写错了，同样要拦。</li>
 * </ol>
 *
 * <p>它不查「值和格式对不对得上」：那要拿真数据试算（ADR-0012 决策 2 的括注）。这里只回答
 * 「这个串本身能不能用」——运行时的宽容语义会把「值和格式对不上」变成**原样透传**（不抛错），
 * 所以那种情况归「有来源字段样例时按样例真跑一遍」那条警告管（{@code MappingSampleChecks}）。</p>
 */
public final class DatePatterns {

    /**
     * 没写格式串时用的默认值。
     *
     * <p>编译器（{@code JsonataRuleCompiler}）、运行时（{@code JsonataFunctions}）与校验（这里）都引用
     * 这一个常量；前端有它自己那份 {@code DEFAULT_DATE_PATTERN}，两边是这个值。</p>
     */
    public static final String DEFAULT = "yyyy-MM-dd";

    /** 说明陷阱字母时拿来举例的参考日期——只是给用户看的例子，不参与判断。 */
    private static final LocalDate TRAP_EXAMPLE = LocalDate.of(2026, 8, 21);

    private DatePatterns() {
    }

    /** 这个格式串能不能用；没写（null / 空白，编译器会用默认值）算能用。 */
    public static boolean isLegal(String pattern) {
        return problem(pattern) == null;
    }

    /** 一条给人看的问题说明；格式串能用时返回 null。 */
    public static String problem(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException e) {
            // 平台的原话（如 Unknown pattern letter: I）比我们另编一句准，照抄过来
            return "日期格式串不合法: " + pattern + "（" + e.getMessage() + "）——运行到这个转换时任务会直接失败";
        }
        String traps = trapLetters(pattern, formatter);
        return traps == null ? null : "日期格式串不合法: " + pattern + "（" + traps + "）";
    }

    /**
     * 陷阱字母的说明；一个都没有时返回 null。
     *
     * <p>同时把这个格式串实际计算一次，把算出来的样子写进说明里——「会写成 2026-08-233」比
     * 「Y 是周所在年」更容易让人立刻看出自己写错了什么。</p>
     */
    private static String trapLetters(String pattern, DateTimeFormatter formatter) {
        List<String> traps = new ArrayList<>();
        if (hasLetter(pattern, 'Y')) {
            traps.add("Y 是「周所在年」不是年");
        }
        if (hasLetter(pattern, 'D')) {
            traps.add("D 是「一年中的第几天」不是日");
        }
        if (traps.isEmpty()) {
            return null;
        }
        return String.join("、", traps) + exampleOf(formatter) + "；年月日请写 " + DEFAULT;
    }

    /** 「拿 2026-08-21 试一下会写成 2026-08-233」；算不出来（理论上不会）就整句省掉。 */
    private static String exampleOf(DateTimeFormatter formatter) {
        try {
            return "，拿 " + TRAP_EXAMPLE + " 试一下会写成 " + TRAP_EXAMPLE.format(formatter);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 单引号里的是字面量（`'T'` 就是字母 T 本身），不算格式字母。
     *
     * <p>连续两个单引号按平台解析器的读法：`''D''` 是「字面量单引号 + 格式字母 D + 字面量单引号」——
     * D 在这里**是**「一年中的第几天」（实测这个格式串把 2026-08-21 写成 `'233'`），所以该拦；
     * 真要让 D 当字面量得写 `'D'`，那一个不拦。整串引号的开关与解析器的状态机等价（`''` 在引号内外
     * 都是转义的单引号，翻两次回到原状态）。</p>
     */
    private static boolean hasLetter(String pattern, char letter) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == letter) {
                return true;
            }
        }
        return false;
    }
}
