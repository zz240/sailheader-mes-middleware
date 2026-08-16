package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.DataSourceType;

/** 新建/更新数据源的请求体；addition 是类型相关的 JSON 配置串。 */
public record DataSourceRequest(
        String name,
        Long siteId,
        DataSourceType type,
        String address,
        String addition
) {
}
