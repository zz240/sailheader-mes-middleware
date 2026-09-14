package com.example.edgegateway.web.dto;

import com.example.edgegateway.mapping.suggest.SourceField;

import java.util.List;
import java.util.Map;

/**
 * 「校验配置」的请求（#103）：编辑器里**还没保存**的那份草稿，加上判断它需要的外部事实。
 *
 * <p>这个接口只读且无状态：收什么由请求决定，不读也不写数据库，也不改 {@code FieldMappingRule} 的格式
 * （#98 D1、#103）。它替代不了 {@code POST /api/jobs/{id}/validate}——那个按 job id 校验已经存进数据库的配置。</p>
 *
 * @param draft                草稿本体，形状与动作 addition 里的字段映射配置一致（{@code {mode? / expression? / rules[]}}）
 * @param targets              目标字段清单（结构探测得到的那一份；探不出来时是编辑器里手写的那些）
 * @param sourceFields         来源样例（结构探测给的字段清单：路径 / 类型 / 样本值）
 * @param earlierActionIndexes 链上执行顺序更早的动作序号——顶层规则的 {@code sourceStep} 只能指向这些
 * @param defaultSourceStep    编辑器顶部那个「默认来源」的步骤（显示「没写 sourceStep 意味着什么」时要用）
 * @param actionIndex          草稿所在动作的序号（新建动作时给它准备占用的那个）；用来认「sourceStep 指到了自己或更晚」
 */
public record MappingDraftRequest(
        Map<String, Object> draft,
        List<TargetFieldSpec> targets,
        List<SourceField> sourceFields,
        List<Integer> earlierActionIndexes,
        Integer defaultSourceStep,
        Integer actionIndex) {

    /**
     * 目标字段清单里的一个字段。
     *
     * <p>{@code element} 是数组元素的字段（子行的目标写的就是相对路径）。这一份清单本轮只有形状上的用处，
     * 基于它的样例检查（必填有没有配、类型配没配上）在下一张票里（#104）。</p>
     */
    public record TargetFieldSpec(String path, String type, Boolean required, List<TargetFieldSpec> element) {
    }
}
