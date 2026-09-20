package com.example.edgegateway.service.sql;

import com.example.edgegateway.model.DataSourceType;

import java.util.List;

/**
 * SQL 方言：同一件事在不同数据库上的写法差异，收在这一处（ADR-0014）。
 *
 * <p>读分批、结构探测、只读校验、幂等 upsert 四处都要拼 SQL，以前各拼各的、且只拼得出 MySQL 的写法。
 * 现在四处都从这里取；加第三种库（Oracle/达梦…）只加一个实现类。</p>
 *
 * <p>实现只负责拼文本，不负责执行；参数顺序与列顺序一致，调用方按同样的顺序绑定。</p>
 */
public interface SqlDialect {

    DataSourceType type();

    /** 读分批：在已经写好水位谓词的 SQL 后面接 keyset 分页。 */
    String paginate(String sql, String orderByColumn, int limit);

    /** 结构探测：取若干行样本来推字段清单。 */
    String probeTable(String table, int limit);

    /** 只读校验：把整条 SQL 包一层、只编译不取数（{watermark} 已由调用方替换）。 */
    String validateProbe(String sql);

    /**
     * 幂等 upsert：命中幂等键则更新非键列，未命中则插入。
     * 参数按 {@code columns} 的顺序绑定（个数 = 列数）。
     */
    String upsert(String table, List<String> columns, String idempotencyKey);
}
