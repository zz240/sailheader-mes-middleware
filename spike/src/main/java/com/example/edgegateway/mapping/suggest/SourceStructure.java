package com.example.edgegateway.mapping.suggest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 源结构（结构探测契约）：key 为源结构指纹（表名/队列名/样例 hash），
 * 用于映射历史按结构隔离；fields 为探测到的字段清单。
 */
public record SourceStructure(
        @JsonProperty("sourceKey") String key,
        List<SourceField> fields) {
}
