package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;

public record DataSourceView(
        Long id,
        Long siteId,
        String name,
        DataSourceType type,
        String address,
        String addition
) {

    public static DataSourceView from(DataSource ds) {
        return new DataSourceView(
                ds.getId(),
                ds.getSiteId(),
                ds.getName(),
                ds.getType(),
                ds.getAddress(),
                ds.getAddition());
    }
}
