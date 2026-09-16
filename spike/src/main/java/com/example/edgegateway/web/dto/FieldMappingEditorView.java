package com.example.edgegateway.web.dto;

import com.example.edgegateway.mapping.suggest.SourceField;

import java.util.List;

/**
 * 字段映射编辑器（新版）打开时需要的上下文（#99）：
 * 目标字段清单从哪里探测、来源字段清单长什么样、每个动作叫什么名字。
 *
 * <p>目标清单与来源样例都只在编辑器打开时存在，不落库（#98 C2）。</p>
 */
public record FieldMappingEditorView(
        Long jobId,
        /** 解析出的写目标（默认取本动作之后最近的 WRITE_UPSERT）；没有则为 null。 */
        WriteTarget writeTarget,
        /** 来源：链上更早的那个动作最近一次成功运行的输出。没有历史时为 null。 */
        SourceSample source,
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
     */
    public record SourceSample(Integer step, String stepName, String sourceKey,
                               boolean sampledFirstItem, List<SourceField> fields) {
    }

    public record ActionLabel(Integer index, String name, String type) {
    }
}
