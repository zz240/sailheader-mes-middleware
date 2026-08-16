package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.DataSourceType;

public record DataSourceRequest(
        String name,
        Long siteId,
        DataSourceType type,
        String address,
        String addition
) {
}
