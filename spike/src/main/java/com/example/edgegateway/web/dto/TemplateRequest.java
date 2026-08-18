package com.example.edgegateway.web.dto;

import java.util.Map;

/** 模板请求体：名称必填，版本可省略（自动按同名最大版本 +1）。 */
public record TemplateRequest(
        String name,
        String version,
        Map<String, Object> configJson
) {
}
