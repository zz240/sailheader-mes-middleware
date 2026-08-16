package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;

import java.util.Map;

/** MySQL 数据源配置：url、username、password 都必填。 */
public record MysqlDataSourceConfig(String url, String username, String password)
        implements DataSourceConfig {

    public static MysqlDataSourceConfig from(Map<String, Object> addition) {
        return new MysqlDataSourceConfig(
                DataSourceConfig.str(addition.get("url")),
                DataSourceConfig.str(addition.get("username")),
                DataSourceConfig.str(addition.get("password")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public void validate() {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MySQL 数据源需要 url");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("MySQL 数据源需要 username");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("MySQL 数据源需要 password");
        }
    }
}
