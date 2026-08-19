package com.example.edgegateway.web.dto;

import com.example.edgegateway.config.ActionConfig;
import com.example.edgegateway.model.ActionKind;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** 动作请求体：kind 决定动作种类，addition 按 kind 转类型化配置（DS-8）。 */
public record ActionRequest(
        String name,
        ActionKind type,
        Integer index,
        ActionConfig addition
) {

    @JsonCreator
    public static ActionRequest from(
            @JsonProperty("name") String name,
            @JsonProperty("type") ActionKind type,
            @JsonProperty("index") Integer index,
            @JsonProperty("addition") Map<String, Object> addition) {
        if (type == null) {
            throw new IllegalArgumentException("不支持的动作类型");
        }
        return new ActionRequest(name, type, index, ActionConfig.from(type, addition));
    }
}
