package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.Template;

import java.util.Map;

/** 模板接口响应体。 */
public record TemplateView(
        Long id,
        String name,
        String version,
        Map<String, Object> configJson
) {

    public static TemplateView from(Template template) {
        return new TemplateView(
                template.getId(),
                template.getName(),
                template.getVersion(),
                template.getConfigJson());
    }
}
