package com.example.edgegateway.service;

import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureProbeServiceTest {

    private final StructureProbeService service = new StructureProbeService();

    @Test
    void probeSampleWalksNestedAndArrayLeafs() {
        java.util.LinkedHashMap<String, Object> items = new java.util.LinkedHashMap<>();
        items.put("name", "x");
        items.put("qty", 3);
        java.util.LinkedHashMap<String, Object> sample = new java.util.LinkedHashMap<>();
        sample.put("order_no", "PO-1");
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", java.util.List.of(items));
        sample.put("data", data);

        SourceStructure structure = service.probeSample(sample);

        assertThat(structure.key()).isEqualTo("sample");
        assertThat(structure.fields()).extracting(f -> f.name())
                .containsExactly("order_no", "data.items[0].name", "data.items[0].qty");
        assertThat(structure.fields()).anyMatch(f ->
                f.name().equals("data.items[0].qty") && f.samples().contains(3));
    }

    @Test
    void rejectsNonMysqlDataSource() {
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.HTTP);

        assertThatThrownBy(() -> service.probeFromDb(ds, "po_order", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅 MySQL");
    }

    @Test
    void rejectsUnsafeTableName() {
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.MYSQL);

        assertThatThrownBy(() -> service.probeFromDb(ds, "po_order; DROP TABLE x", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名不合法");
    }
}
