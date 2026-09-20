package com.example.edgegateway.service.sql;

import com.example.edgegateway.model.DataSourceType;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** MySQL 方言（与 ADR-0014 之前散在四处站点里的写法逐字一致，行为不变）。 */
public final class MySqlDialect implements SqlDialect {

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public String paginate(String sql, String orderByColumn, int limit) {
        return sql + " ORDER BY " + orderByColumn + " LIMIT " + limit;
    }

    @Override
    public String probeTable(String table, int limit) {
        return "SELECT * FROM " + table + " LIMIT " + limit;
    }

    @Override
    public String validateProbe(String sql) {
        return "SELECT * FROM ( " + sql + " ) __probe LIMIT 0";
    }

    @Override
    public String upsert(String table, List<String> columns, String idempotencyKey) {
        String columnList = String.join(",", columns);
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String updates = columns.stream()
                .filter(c -> !c.equals(idempotencyKey))
                .map(c -> c + " = values(" + c + ")")
                .collect(Collectors.joining(", "));
        return "insert into " + table + " (" + columnList + ") values (" + placeholders + ")"
                + " on duplicate key update "
                + (updates.isEmpty() ? idempotencyKey + " = values(" + idempotencyKey + ")" : updates);
    }
}
