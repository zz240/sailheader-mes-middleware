package com.example.edgegateway.service.sql;

import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 方言的行为规格（ADR-0014）。
 *
 * <p>测的是方言的公共接口产出什么 SQL 文本——期望值逐字取自 ADR-0014 的「四处对照表」，
 * 不是照实现抄的。不连库、不碰实现细节：换实现（例如把 MERGE 换成别的幂等写法），
 * 只有「文本变了」才该红。</p>
 */
class SqlDialectTest {

    private final SqlDialect mysql = SqlDialects.of(DataSourceType.MYSQL);
    private final SqlDialect sqlserver = SqlDialects.of(DataSourceType.SQLSERVER);

    // ---------- 读分批（keyset 分页） ----------

    @Test
    void mysqlPaginatesWithLimit() {
        assertEquals("select id, order_no from po_order where id > ? ORDER BY id LIMIT 50",
                mysql.paginate("select id, order_no from po_order where id > ?", "id", 50));
    }

    @Test
    void sqlServerPaginatesWithOffsetFetch() {
        assertEquals("select id, FBillNo from POOrder where FInterID > ?"
                        + " ORDER BY FInterID OFFSET 0 ROWS FETCH NEXT 50 ROWS ONLY",
                sqlserver.paginate("select id, FBillNo from POOrder where FInterID > ?", "FInterID", 50));
    }

    // ---------- 结构探测 ----------

    @Test
    void mysqlProbesWithLimit() {
        assertEquals("SELECT * FROM po_order LIMIT 20", mysql.probeTable("po_order", 20));
    }

    @Test
    void sqlServerProbesWithTop() {
        assertEquals("SELECT TOP 20 * FROM POOrder", sqlserver.probeTable("POOrder", 20));
    }

    // ---------- 只读校验 ----------

    @Test
    void mysqlValidatesByWrappingWithLimitZero() {
        assertEquals("SELECT * FROM ( select id from po_order where 1=1 ) __probe LIMIT 0",
                mysql.validateProbe("select id from po_order where 1=1"));
    }

    @Test
    void sqlServerValidatesByWrappingWithTopZero() {
        assertEquals("SELECT TOP 0 * FROM ( select FInterID from POOrder where 1=1 ) __probe",
                sqlserver.validateProbe("select FInterID from POOrder where 1=1"));
    }

    // ---------- 幂等 upsert ----------

    @Test
    void mysqlUpsertsWithOnDuplicateKeyUpdate() {
        assertEquals("insert into po_receipt (order_no,material,qty) values (?,?,?)"
                        + " on duplicate key update material = values(material), qty = values(qty)",
                mysql.upsert("po_receipt", List.of("order_no", "material", "qty"), "order_no"));
    }

    @Test
    void sqlServerUpsertsWithMerge() {
        assertEquals("MERGE INTO po_receipt WITH (HOLDLOCK) AS tgt"
                        + " USING (VALUES (?,?,?)) AS src (order_no,material,qty)"
                        + " ON tgt.order_no = src.order_no"
                        + " WHEN MATCHED THEN UPDATE SET material = src.material, qty = src.qty"
                        + " WHEN NOT MATCHED THEN INSERT (order_no,material,qty)"
                        + " VALUES (src.order_no,src.material,src.qty);",
                sqlserver.upsert("po_receipt", List.of("order_no", "material", "qty"), "order_no"));
    }

    /** 只有幂等键一列时，两种方言都必须给出一句合法 SQL（MySQL 的 SET 不能空，MERGE 的 UPDATE 也不能空）。 */
    @Test
    void keyOnlyRowStillProducesValidSql() {
        assertEquals("insert into t (k) values (?) on duplicate key update k = values(k)",
                mysql.upsert("t", List.of("k"), "k"));
        assertEquals("MERGE INTO t WITH (HOLDLOCK) AS tgt"
                        + " USING (VALUES (?)) AS src (k)"
                        + " ON tgt.k = src.k"
                        + " WHEN MATCHED THEN UPDATE SET k = src.k"
                        + " WHEN NOT MATCHED THEN INSERT (k) VALUES (src.k);",
                sqlserver.upsert("t", List.of("k"), "k"));
    }

    /** 键列不出现在 UPDATE 集合里（改了它等于换了一行）。 */
    @Test
    void idempotencyKeyIsNotUpdated() {
        String sql = sqlserver.upsert("po_receipt", List.of("order_no", "qty"), "order_no");
        assertTrue(sql.contains("USING (VALUES (?,?)) AS src (order_no,qty)"), sql);
        assertTrue(sql.contains("UPDATE SET qty = src.qty"), sql);
        assertFalse(sql.contains("UPDATE SET order_no"), sql);
    }

    // ---------- 取方言 ----------

    @Test
    void jdbcTypesHaveDialectsAndOthersDoNot() {
        assertTrue(SqlDialects.isJdbc(DataSourceType.MYSQL));
        assertTrue(SqlDialects.isJdbc(DataSourceType.SQLSERVER));
        assertFalse(SqlDialects.isJdbc(DataSourceType.HTTP));
        assertFalse(SqlDialects.isJdbc(DataSourceType.RABBITMQ));
        assertEquals(DataSourceType.SQLSERVER, sqlserver.type());
        assertEquals(Set.of(DataSourceType.MYSQL, DataSourceType.SQLSERVER), SqlDialects.jdbcTypes());
        assertThrows(IllegalArgumentException.class, () -> SqlDialects.of(DataSourceType.HTTP));
    }
}
