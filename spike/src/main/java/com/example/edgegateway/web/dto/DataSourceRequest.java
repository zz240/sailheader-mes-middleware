package com.example.edgegateway.web.dto;

import com.example.edgegateway.model.DataSourceType;

import com.example.edgegateway.config.datasource.DataSourceConfig;
import com.example.edgegateway.model.DataSourceType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 新建/更新数据源的请求体；addition 按请求根上的 type 转成类型化配置
 * （EXTERNAL_PROPERTY 多态对 record 不可用，改由 @JsonCreator 手工分派）。
 */
public record DataSourceRequest(
        String name,
        Long siteId,
        DataSourceType type,
        String address,
        DataSourceConfig addition
) {

    @JsonCreator
    public static DataSourceRequest from(
            @JsonProperty("name") String name,
            @JsonProperty("siteId") Long siteId,
            @JsonProperty("type") DataSourceType type,
            @JsonProperty("address") String address,
            @JsonProperty("addition") Map<String, Object> addition) {
        if (type == null) {
            throw new IllegalArgumentException("不支持的数据源类型");
        }
        return new DataSourceRequest(name, siteId, type, address, DataSourceConfig.from(type, addition));
    }
}
