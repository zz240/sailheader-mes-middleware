package com.example.edgegateway.web.dto;

import java.util.List;

/**
 * 「试算」的响应（#116 / #120）：这份草稿在那份样例上**真跑**出来的目标报文，或者「没跑通 + 哪几行有问题」，
 * 外加一张**逐字段的状态表**。
 *
 * <p>两段式（ADR-0012 决策 6）：{@code ran=true} 时 {@code output} 是**整份跑**的结果（与真实执行
 * 完全一致的那一次）；{@code ran=false} 时 {@code error} 是整份跑抛出来的那句话，{@code failures}
 * 才是**单独跑某一行**的结果——它是定位手段，不是「真实执行会这样」。界面上必须把这层区别写出来
 * （「整份没跑通，标红的是单独跑这一行的结果」）。</p>
 *
 * <p>逐字段状态表（#120）回答的是另一件事：目标报文里**少了一个键**时，是他**没配**、还是**配了但取不到值**、
 * 还是**算出来就是 null**。这三件事在最后那个 JSON 里长得一模一样（前两种键都不在），{@link FieldStatus}
 * 把它们分开。它只在整份跑通时有值（没跑通时没有报文可对，{@code fields} 是空列表、
 * {@code metrics} 全是 0）。</p>
 *
 * <p>加这两段对老前端是兼容的（Jackson 多给几个键）：不读它们的调用方照常工作。</p>
 *
 * @param ran      整份跑通了没有
 * @param output   目标报文（整份跑通时）；没跑通为 null
 * @param error    整份没跑通时那句话（编译不过、求值抛错、撞上超时/递归上限都在这里）；跑通了为 null
 * @param failures 失败归因：单独跑这一行时失败的行与原因；整份跑通时是空列表，手写表达式那一边也空
 *                 （没有「行」可归因——那边只有一整段表达式）
 * @param fields   逐字段状态表：每个目标字段一行（数组组行与它的元素行都占一行）；没跑通时是空列表
 * @param metrics  一句整合数：目标叶子字段几落值、几省略、几未映射，外加目标数组几行；没跑通时全是 0
 */
public record MappingTrialResult(
        boolean ran,
        Object output,
        String error,
        List<Failure> failures,
        List<FieldStatus> fields,
        Metrics metrics) {

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

    /**
     * 一个目标字段在这次试算里的四档状态（#120）。
     *
     * <p>{@code NULL} 与 {@code DROPPED} 必须分开：前者是「算出来就是 null」（键在、值是 null），
     * 后者是「配了，但键被引擎丢掉了」——这正是本票的核心价值。</p>
     */
    public enum FieldState {
        /** 结果里有这个键、值不是 null。 */
        VALUED,
        /** 配了规则，但结果里没有这个键（来源取不到值 / 枚举查不到键时引擎**把键丢掉**，不是变成 null）。 */
        DROPPED,
        /** 键在、值就是 null。 */
        NULL,
        /** 目标清单里有、草稿里没有这条规则（请求带了目标清单才判得出来）。 */
        UNMAPPED
    }

    /**
     * 逐字段状态表里的一行。
     *
     * <p>定位口径与「校验配置」那份问题列表**一模一样**（{@code target} + {@code parent}）：
     * 顶层字段的 {@code target} 是完整目标路径、{@code parent} 为 null；数组元素子行的 {@code target}
     * 写的是相对路径、{@code parent} 是它所属数组行的目标字段。界面据此**复用同一份跳行代码**。</p>
     *
     * @param target    目标字段路径（数组元素子行写相对路径）
     * @param parent    这一行所属数组行的目标字段；顶层行为 null
     * @param value     试算值：顶层字段是那个值本身，数组元素字段是**按元素分开**的一组取值
     *                  （某个元素没落这个键时那一格是 null）；数组组行不给值（看 {@code arrayRows}）；
     *                  没落值的行是 null
     * @param status    四档状态
     * @param reason    为什么被丢掉（{@link FieldState#DROPPED} 独有，其余为 null）；引擎自己抛了错就用
     *                  它那句原话，否则用与仓库现有说法同一口径的一句
     * @param arrayRows 数组组行落了几行；非组行为 null（没落值时为 0）
     */
    public record FieldStatus(
            String target,
            String parent,
            Object value,
            FieldState status,
            String reason,
            Integer arrayRows) {
    }

    /**
     * 一句整合数（界面上的计量条）。
     *
     * <p>算术闭合：{@code valued + dropped + unmapped = targets}。{@code targets} 只数**叶子字段**
     * ——数组组行本身不算一个目标字段（它的元素字段才算），所以这张表的行数会比 {@code targets} 多出
     * 几个组行。算出来是 null 的字段计在 {@code valued} 里（结果里有这个键），另在证据表那一行标出来。</p>
     *
     * @param targets   目标叶子字段总数（清单里的 + 这次真算出来的）
     * @param valued    结果里有这个键的字段数（含算出来是 null 的）
     * @param dropped   配了规则但键被丢掉的字段数（静默省略）
     * @param unmapped  目标清单里有、草稿里没配的字段数（没带清单时恒为 0）
     * @param arrayRows 目标数组一共落了几行
     */
    public record Metrics(int targets, int valued, int dropped, int unmapped, int arrayRows) {

        /** 没什么可数的那些情况（整份没跑通、没有报文可对）用的零值。 */
        public static Metrics none() {
            return new Metrics(0, 0, 0, 0, 0);
        }
    }
}
