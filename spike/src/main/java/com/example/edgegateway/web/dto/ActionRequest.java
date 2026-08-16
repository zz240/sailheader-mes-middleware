package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.ActionType;

import java.util.Map;

/** 动作请求体；远程访问动作可在 addition 里带 dataSourceId 引用已存在的数据源。 */
public record ActionRequest(
        String name,
        ActionType type,
        Integer index,
        Map<String, Object> addition
) {
}
