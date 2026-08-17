package com.example.edgegateway.web.dto;

import java.util.List;

/** 映射建议请求：任务 + 源结构（探测契约）+ 目标字段清单。 */
public record SuggestionRequest(
        Long jobId,
        Source source,
        List<Target> targets
) {

    public record Source(String sourceKey, List<Field> fields) {
    }

    public record Field(String name, String type, Boolean nullable, List<Object> samples) {
    }

    public record Target(String name, Boolean required) {
    }
}
