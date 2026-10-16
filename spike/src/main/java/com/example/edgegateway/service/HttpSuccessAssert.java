package com.example.edgegateway.service;

import com.example.edgegateway.config.action.HttpCallConfig;

import java.util.Map;

/**
 * HTTP_CALL 的**成功条件**断言（#148）：HTTP 状态码只管传输层，业务结果由响应体里的字段说了算——
 * 金蝶 / 用友系 WebAPI 常见的约定是"永远 200，业务错误写在响应体里"。
 *
 * <p>网关不知道哪家的哪个字段代表成功，所以它不硬编码任何业务语义：由配置声明
 * {@code successPath}（在响应体里取值的点分路径，如 {@code code} / {@code data.status}）与
 * {@code successExpect}（期望值）。四条判定：</p>
 *
 * <ol>
 *   <li>{@code successPath} 留空 → 整段不生效，与没有这个功能时完全一致（存量链零影响）；</li>
 *   <li>{@code successExpect} 留空 → 只判**存在**（路径取到值即通过，取到 {@code 0} 也算）；</li>
 *   <li>两格都写 → 取到的值与期望值**字符串归一**后相等才通过；</li>
 *   <li>取到的是对象 / 数组 → 显式失败，**不比**：成功条件只判标量，"成功标记本身是个对象"
 *       是配置错误，{@code String.valueOf} 吐出来的 {@code {a=1}} 却可能**碰巧**等于配置里写的期望值。</li>
 * </ol>
 *
 * <p>字符串归一是刻意的：响应里 {@code "code":0} 与配置里写 {@code "0"} 是同一件事，
 * 严格类型比较会造成"看起来一样却判失败"。</p>
 *
 * <p>它是纯逻辑（只吃解析好的响应对象与原文），所以取值的路径遍历复用链上唯一那一份
 * {@link ElementMessageFactory#valueAt}（与拆分来源路径同一实现），不另写一份；
 * 「只填期望值」这条配置规则的判定归 {@link HttpCallConfig#requireSuccessPathWithExpect}，
 * 与写端点的校验共用同一份（见那里的说明）。</p>
 */
public final class HttpSuccessAssert {

    /** 错误信息里响应原文的截断长度：截断只为可读，{@code data_snapshot} 仍存完整原文。 */
    private static final int RAW_EXCERPT_CHARS = 200;

    private HttpSuccessAssert() {
    }

    /**
     * 不满足成功条件即抛 {@link IllegalStateException}（走网关既有失败面：重试退避 → FAILED 消息步 →
     * 运行 FAILED → 水位不推进）。
     *
     * <p>调用点必须在 {@code recordSuccessStep} **之前**——先写一行 SUCCESS 再记一行 FAILED 会留下半截记录。</p>
     *
     * @param method       出站用的 HTTP 方法（只为错误信息可读）
     * @param url          出站用的完整地址（同上）
     * @param successPath  响应体里的取值路径；留空即不启用断言
     * @param successExpect 期望值；留空即只判存在
     * @param response     {@link HttpCallResponse#parse} 的结果
     * @param raw          响应原文（错误信息里取片段）
     */
    public static void check(String method, String url, String successPath, String successExpect,
                             Object response, String raw) {
        if (HttpCallConfig.isBlank(successPath)) {
            // 光有期望值不知道该看响应体的哪里——配置期就该被 validate 拦下，运行期遇到也得说出来
            HttpCallConfig.requireSuccessPathWithExpect(successPath, successExpect);
            return;
        }
        Object actual = ElementMessageFactory.valueAt(response, successPath);
        if (actual == null) {
            throw new IllegalStateException(failureMessage(method, url,
                    "响应 " + successPath + " 没有取到值（缺失或为 null），"
                            + unsatisfiedCondition(successPath, successExpect), raw));
        }
        if (isStructured(actual)) {
            throw new IllegalStateException(failureMessage(method, url,
                    "响应 " + successPath + " 取到的是" + (actual instanceof Map ? "对象" : "数组")
                            + "而不是一个值，成功条件只判标量（字符串 / 数字 / 布尔）"
                            + "——successPath 指到对象 / 数组上是配置错误，不是比较", raw));
        }
        if (!HttpCallConfig.isBlank(successExpect) && !String.valueOf(actual).equals(String.valueOf(successExpect))) {
            throw new IllegalStateException(failureMessage(method, url,
                    "响应 " + successPath + "=" + actual + "，"
                            + unsatisfiedCondition(successPath, successExpect), raw));
        }
    }

    /** 取到的是对象 / 数组：成功条件只判标量，这两种直接判配置错误，绝不拿 Java toString 去比。 */
    private static boolean isStructured(Object value) {
        return value instanceof Map || value instanceof Iterable;
    }

    /**
     * 失败信息的口径：把「怎么判定失败的」（判定依据 + 期望）与「目标端到底回了什么」（响应原文片段）
     * 写在一句里，可以直接贴给对接方。
     */
    private static String failureMessage(String method, String url, String diagnosis, String raw) {
        return "目标端业务失败：" + method + " " + url + " " + diagnosis
                + "；响应原文" + excerptOrEmptyBody(raw);
    }

    /**
     * 成功条件在错误信息里的说法（连「不满足」一起，免得两种档各自拼一遍空格）：
     * 留空期望值 = 只判**存在**，否则 = 等值比较。
     * 只判存在那档原先写成「{@code <path> 有值 不满足}」，读不通；两档各说各的话。
     */
    private static String unsatisfiedCondition(String successPath, String successExpect) {
        return HttpCallConfig.isBlank(successExpect)
                ? "成功条件「" + successPath + " 必须存在」不满足"
                : "成功条件 " + successPath + "=" + successExpect + " 不满足";
    }

    /**
     * 响应体为空那一档单说一句：配了成功条件却什么都没收到，是**协议违例**，
     * 与"收到了但字段不对"是两回事，错误信息里得让人一眼分辨——所以这个名字管两档，
     * 既是"截断后的原文"也是"没有原文"。
     */
    private static String excerptOrEmptyBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return "（响应体为空）";
        }
        return "前 " + RAW_EXCERPT_CHARS + " 字符：" + (raw.length() <= RAW_EXCERPT_CHARS
                ? raw
                : raw.substring(0, RAW_EXCERPT_CHARS));
    }
}
