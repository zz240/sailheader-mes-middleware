package com.example.edgegateway.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTableExtractorTest {

    @Test
    void extractTableHandlesQuotedAndSchemaQualifiedNames() {
        assertThat(SqlTableExtractor.extractTable("SELECT * FROM po_order WHERE id > 1"))
                .isEqualTo("po_order");
        assertThat(SqlTableExtractor.extractTable("select a,b from `erp`.po_order o"))
                .isEqualTo("po_order");
        assertThat(SqlTableExtractor.extractTable("SELECT 1")).isNull();
    }
}
