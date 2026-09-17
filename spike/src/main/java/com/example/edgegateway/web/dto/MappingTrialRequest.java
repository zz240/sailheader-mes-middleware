package com.example.edgegateway.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 「试算」的请求（#116 / #120）：编辑器里**还没保存**的那份草稿，加上**真跑那份来源样例**，
 * 外加一份**目标字段清单**（判断「清单里有、你却没配」用）。
 *
 * <p>与「校验配置」（{@link MappingDraftRequest}）分开是刻意的：那个是静态查（便宜、保存前必跑），
 * 这个是真执行表达式（要资源上限、点按钮才跑）。两者只共用「草稿」这一格的写法，其余输入各要各的
 * ——校验要目标清单与来源字段清单来判断，试算要**那份数据本身**（外加同一份目标清单来照亮未映射）。</p>
 *
 * <p>只读且无状态：不落库、不改配置（#116 验收八）。</p>
 *
 * @param draft        草稿本体，形状与动作 addition 里的字段映射配置一致（{@code {mode? / expression? / rules[]}}）
 * @param sampleJson   要跑的那份来源样例的**原文**（编辑器左边那份的原文；对象或数组，数组按第 1 条，
 *                     与推字段清单同一口径——清单是这份数据的纯函数，试算跑的必须是同一份数据）。
 *                     没有样例时是 null，那时没什么可跑的。
 * @param sampleStep   这份样例算在哪一步（0 = 任务输入）：规则里写 {@code sourceStep=N} 的就去 {@code $s_N}
 *                     取数据。别的步骤这一份样例里没有，取到的是 null——与真跑时「那一步还没发生」一个道理。
 * @param targetFields 目标字段清单，与校验请求里的 {@code targets} **同一份**（{@link MappingDraftRequest.TargetFieldSpec}）。
 *                     这一格是硬性的：{@code UNMAPPED}（清单里有 5 个、你配了 4 个）只能靠它才知道；
 *                     传 null 时 {@code UNMAPPED} 一律不判，其余状态照常给。定位口径沿用校验结果那一套
 *                     （{@code target} + {@code parent}），这样界面上「点一下跳到那一行」能复用同一份代码。
 */
public record MappingTrialRequest(
        Map<String, Object> draft,
        String sampleJson,
        Integer sampleStep,
        List<MappingDraftRequest.TargetFieldSpec> targetFields) {
}
