package com.example.edgegateway.web.dto;

import java.util.List;

/**
 * 「试算」的响应（#116）：这份草稿在那份样例上**真跑**出来的目标报文，或者「没跑通 + 哪几行有问题」。
 *
 * <p>两段式（ADR-0012 决策 6）：{@code ran=true} 时 {@code output} 是**整份跑**的结果（与真实执行
 * 完全一致的那一次）；{@code ran=false} 时 {@code error} 是整份跑抛出来的那句话，{@code failures}
 * 才是**单独跑某一行**的结果——它是定位手段，不是「真实执行会这样」。界面上必须把这层区别写出来
 * （「整份没跑通，标红的是单独跑这一行的结果」）。</p>
 *
 * @param ran      整份跑通了没有
 * @param output   目标报文（整份跑通时）；没跑通为 null
 * @param error    整份没跑通时那句话（编译不过、求值抛错、撞上超时/递归上限都在这里）；跑通了为 null
 * @param failures 失败归因：单独跑这一行时失败的行与原因；整份跑通时是空列表，手写表达式那一边也空
 *                 （没有「行」可归因——那边只有一整段表达式）
 */
public record MappingTrialResult(
        boolean ran,
        Object output,
        String error,
        List<Failure> failures) {

    /**
     * 一行单独跑出来的失败。
     *
     * @param target 出错那一行的目标字段（数组元素子行写的是相对路径）
     * @param parent 这一行所属数组行的目标字段；顶层行为 null。数组套数组时是它**直属于**的那一层
     *               ——与校验结果那份定位口径一样只有一层（界面按「外层 · 内层」显示）
     * @param message 引擎抛出来的那句原话（是谁的错由它说，这边不转述）
     */
    public record Failure(String target, String parent, String message) {
    }
}
