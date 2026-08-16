package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;

import java.util.Map;

/** HTTP 数据源配置：请求方法必填。 */
public record HttpDataSourceConfig(String method) implements DataSourceConfig {

    public static HttpDataSourceConfig from(Map<String, Object> addition) {
        return new HttpDataSourceConfig(ConfigValues.str(addition.get("method")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.HTTP;
    }

    @Override
    public void validate() {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("HTTP 数据源需要 method");
        }
    }
}
