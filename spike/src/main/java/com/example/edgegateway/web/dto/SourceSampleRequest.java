package com.example.edgegateway.web.dto;

/**
 * 「粘贴 JSON」那条路的请求体（#115）：一份样例报文 + 它归到哪个来源步骤。
 *
 * <p>{@code json} 是**原文**（不带引号的 JSON 文本），解析与字段推断都在服务端做——两条路共用同一份
 * 样例展开，形状才对得上。{@code step} 必填：来源步骤为空会让基于样例的那几条检查静默全部不判。</p>
 */
public record SourceSampleRequest(Integer step, String json) {
}
