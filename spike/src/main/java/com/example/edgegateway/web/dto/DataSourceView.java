package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;

import java.util.Map;

/** 数据源接口响应体；from(DataSource) 负责实体到视图的转换。 */
public record DataSourceView(
        Long id,
        Long siteId,
        String name,
        DataSourceType type,
        String address,
        Map<String, Object> addition
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
