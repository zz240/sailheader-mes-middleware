package com.example.edgegateway.web.dto;

/**
 * 「粘贴 JSON」拿**目标**字段清单那条路的请求体（#118）。
 *
 * <p>只有 {@code json}：目标这一侧没有「归到哪一步」那回事（来源侧才有，见 {@link SourceSampleRequest}）。
 * {@code json} 是**原文**（不带引号的 JSON 文本），解析与字段推断都在服务端做——与来源侧共用同一份。</p>
 */
public record TargetSampleRequest(String json) {
}
