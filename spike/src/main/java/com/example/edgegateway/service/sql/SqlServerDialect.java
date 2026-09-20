package com.example.edgegateway.service.sql;

import com.example.edgegateway.model.DataSourceType;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL Server 方言（ADR-0014）。
 *
 * <p>三处与 MySQL 的差别：分页用 {@code OFFSET/FETCH}（2012+，且必须有 ORDER BY，与 keyset 分页天然一致）、
 * 取前 N 行用 {@code TOP}、upsert 用 {@code MERGE}。</p>
 *
 * <p>{@code MERGE} 配 {@code HOLDLOCK}：不加的话并发下可能撞上唯一键；{@code USING (VALUES (?,?)) AS src(c1,c2)}
 * 这个写法是为了避开 SQL Server 对「只在 USING 里出现的参数」推断不出类型的毛病。
 * 用 {@code MERGE} 而不是 {@code UPDATE ... IF @@ROWCOUNT=0 INSERT}：后者在并发下有竞态。</p>
 */
public final class SqlServerDialect implements SqlDialect {

    @Override
    public DataSourceType type() {
        return DataSourceType.SQLSERVER;
    }

    @Override
    public String paginate(String sql, String orderByColumn, int limit) {
        return sql + " ORDER BY " + orderByColumn
                + " OFFSET 0 ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String probeTable(String table, int limit) {
        return "SELECT TOP " + limit + " * FROM " + table;
    }

    @Override
    public String validateProbe(String sql) {
        return "SELECT TOP 0 * FROM ( " + sql + " ) __probe";
    }

    @Override
    public String upsert(String table, List<String> columns, String idempotencyKey) {
        String columnList = String.join(",", columns);
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String sourceColumns = columns.stream().map(c -> "src." + c).collect(Collectors.joining(","));
        String updates = columns.stream()
                .filter(c -> !c.equals(idempotencyKey))
                .map(c -> c + " = src." + c)
                .collect(Collectors.joining(", "));
        return "MERGE INTO " + table + " WITH (HOLDLOCK) AS tgt"
                + " USING (VALUES (" + placeholders + ")) AS src (" + columnList + ")"
                + " ON tgt." + idempotencyKey + " = src." + idempotencyKey
                + " WHEN MATCHED THEN UPDATE SET "
                + (updates.isEmpty() ? idempotencyKey + " = src." + idempotencyKey : updates)
                + " WHEN NOT MATCHED THEN INSERT (" + columnList + ") VALUES (" + sourceColumns + ");";
    }
}
