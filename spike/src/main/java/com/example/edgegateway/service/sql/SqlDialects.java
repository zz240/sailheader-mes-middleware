package com.example.edgegateway.service.sql;

import com.example.edgegateway.model.DataSourceType;

import java.util.Map;
import java.util.Set;

/** 方言入口：按数据源类型取方言（ADR-0014）。非 JDBC 类型（HTTP/RABBITMQ）没有方言。 */
public final class SqlDialects {

    private static final Map<DataSourceType, SqlDialect> BY_TYPE = Map.of(
            DataSourceType.MYSQL, new MySqlDialect(),
            DataSourceType.SQLSERVER, new SqlServerDialect());

    private SqlDialects() {
    }

    /** 这个类型是不是走 JDBC 的库（决定要不要建连接池、能不能读写表）。 */
    public static boolean isJdbc(DataSourceType type) {
        return type != null && BY_TYPE.containsKey(type);
    }

    /** 所有走 JDBC 的类型：建池与「按类型筛数据源」的地方用它，避免各处硬写枚举清单。 */
    public static Set<DataSourceType> jdbcTypes() {
        return BY_TYPE.keySet();
    }

    public static SqlDialect of(DataSourceType type) {
        SqlDialect dialect = type == null ? null : BY_TYPE.get(type);
        if (dialect == null) {
            throw new IllegalArgumentException("该数据源类型没有 SQL 方言: " + type);
        }
        return dialect;
    }
}
