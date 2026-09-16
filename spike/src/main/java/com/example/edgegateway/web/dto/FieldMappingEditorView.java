package com.example.edgegateway.web.dto;

import com.example.edgegateway.mapping.suggest.SourceField;

import java.util.List;

/**
 * 字段映射编辑器（新版）打开时需要的上下文（#99）：
 * 目标字段清单从哪里探测、来源字段清单长什么样、每个动作叫什么名字。
 *
 * <p>打开时**先看库里存过的那份**（#119）：这一步 / 这个写目标动作粘过一次并保存过 → 就用那一份
 * （`source` / `targetSample` 带着原文回来，`sourceKey` 记成 `stored`）；没存过 → 走探测那条路，与
 * #119 之前一字不差。探测那份本身**不落库**（修订 ADR-0012 决策 5）。</p>
 */
public record FieldMappingEditorView(
        Long jobId,
        /** 解析出的写目标（默认取本动作之后最近的 WRITE_UPSERT）；没有则为 null。 */
        WriteTarget writeTarget,
        /** 来源：库里存过的那份粘的（#119），否则是链上更早的那个动作最近一次成功运行的输出。 */
        SourceSample source,
        /** 库里给这个写目标存过的那份目标样例（#119）；没存过是 null——目标清单走连库探测那条路。 */
        TargetSample targetSample,
        /** 动作序号 → 动作名，界面上把 `$s_N` 显示成「动作 N · 名称」用。 */
        List<ActionLabel> actions) {

    /** 写目标：目标结构从这个数据源的表探测出来（tableName 直接透传给探测接口）。 */
    public record WriteTarget(Long actionId, Integer actionIndex, String actionName,
                              Long dataSourceId, String tableName) {
    }

    /**
     * 来源样例：推出来的字段清单，字段形状沿用结构探测契约（SourceField）；来源是运行记录或粘进来的 JSON。
     *
     * <p>`sampledFirstItem` 说的是「这份样例是一整个数组，只按第 1 条推的」——界面据此注明
     * 「按第 1 条推的」；整份就是一个对象（或运行记录那条路）时为 false。</p>
     *
     * @param sourceKey `paste` = 这一次粘进来的；`stored` = 库里存过的那份（#119）；其余是探测那条路
     *                  （运行快照给 `sample`，连库那条路给 `db:<表名>`）——界面按它选说法
     * @param json      粘的那份**原文**（#115 / #119）；探测那条路是 null。保存时原样写回库里（#119），
     *                  所以存过的那份重开之后手里仍然握着原文（这也正是「存原文」的另一半理由）
     */
    public record SourceSample(Integer step, String stepName, String sourceKey,
                               boolean sampledFirstItem, List<SourceField> fields, String json) {
    }

    /**
     * 目标字段清单（编辑器那一侧）：来源是连库探测、粘进来的一份样例（#118），或库里存过的那份（#119）。
     *
     * <p>fields 形状与结构探测契约（{@code mapping.suggest.SourceStructure}）一致，编辑器那一侧两边走同一份
     * 折算；多一个 `sampledFirstItem`：粘的是一整个数组时只按第 1 条推，界面据此注明。</p>
     *
     * @param sourceKey 与来源那侧的 `sourceKey` 同一个口径：`paste` = 这一次粘的，`stored` = 库里存过的那份
     *                  （#119）。#118 当时不带这一格，理由是「粘的这条路调用方自己就知道」——#119 之后
     *                  **两种粘**要分开（界面写明「粘的（已存）」），所以这一格现在有读者了
     * @param json      粘的那份原文（#118 / #119）；连库探测那条路（{@code SourceStructure}）没有这一格
     */
    public record TargetSample(String sourceKey, boolean sampledFirstItem, List<SourceField> fields, String json) {
    }

    public record ActionLabel(Integer index, String name, String type) {
    }
}
