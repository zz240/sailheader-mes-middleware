package com.example.edgegateway.config.datasource;

/**
 * 走 JDBC 的关系库数据源配置（MySQL / SQL Server）。
 *
 * <p>连接 url 取数据源的 {@code address}，addition 只放账号密码——于是「连库」「结构探测」「只读校验」
 * 这些不关心具体是哪种库的地方，只认这一层；具体库的 SQL 写法差异归方言（ADR-0014）。</p>
 */
public sealed interface JdbcDataSourceConfig extends DataSourceConfig
        permits MysqlDataSourceConfig, SqlServerDataSourceConfig {

    String username();

    String password();
}
