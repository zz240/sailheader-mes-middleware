package com.example.edgegateway.web.dto;

import java.util.List;

/**
 * 「校验配置」的响应（#103）：问题列表 + 编译产物文本。
 *
 * <p>每条问题都带目标字段路径（{@code target} 加 {@code parent}），界面据此定位到行；
 * 定位不到某一行的问题（整份草稿级别，例如「至少一条映射规则」）两个都可以为空。</p>
 *
 * <p>和任务级那个 {@link ValidationResult} 长得像但不是一回事：那个的问题按「范围 + 字段名」定位
 * （scope/field），校验的是**已经存进数据库**的配置；这个按「目标字段 + 它所属的数组行」定位，
 * 校验的是编辑器里还没保存的草稿。两个端点各说各的定位方式，所以各留一份自己的问题形状。</p>
 *
 * @param valid    有没有错误（警告不算；和 {@code errors.isEmpty()} 一个意思，省得界面自己算）
 * @param errors   错误：这份草稿现在存下去后端会拒——只有结构错与链问题（#113 / ADR-0012 决策 4）
 * @param warnings 警告：能存能跑，但结果大概不是你要的（只在来源字段样例上成立的那几条都归这里）
 * @param compiled 编译产物文本；草稿还没法编译时两个字段都是 null
 */
public record MappingDraftValidation(
        boolean valid,
        List<Problem> errors,
        List<Problem> warnings,
        Compiled compiled) {

    /**
     * 一条问题。
     *
     * @param target  出错那条规则的目标字段（数组元素子行写的是相对路径），和编辑器里行上的目标字段同一个值
     * @param parent  这条规则所属数组行的目标字段；顶层规则为 null
     * @param message 说明（后端已有校验/编译器给的原话，前后端不各写一套）
     */
    public record Problem(String target, String parent, String message) {
    }

    /**
     * 编译产物文本，两个标签页（#103）。
     *
     * @param jsonata   编译出的 JSONata 表达式；{@code mode=EXPRESSION} 时就是那份手写表达式
     * @param rulesJson 规则 JSON：后端按现在的规则读出来的那一份（{@code mode? / expression? / rules[]}）
     */
    public record Compiled(String jsonata, String rulesJson) {
    }
}
