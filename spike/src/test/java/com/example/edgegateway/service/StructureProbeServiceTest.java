package com.example.edgegateway.service;

import com.example.edgegateway.mapping.suggest.SourceStructure;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.DataSourceMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.ActionKind;
import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructureProbeServiceTest {

    private final ActionMapper actionMapper = mock(ActionMapper.class);
    private final DataSourceMapper dataSourceMapper = mock(DataSourceMapper.class);
    private final StructureProbeService service =
            new StructureProbeService(actionMapper, dataSourceMapper);

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

    @Test
    void suggestTableExtractsFromJobReadSql() {
        Action action = new Action();
        action.setType(ActionKind.READ_SQL);
        action.setAddition(Map.of("dataSourceId", 7, "sql", "SELECT * FROM po_order WHERE id > {watermark}"));
        when(actionMapper.selectList(any())).thenReturn(List.of(action));

        assertThat(service.suggestTable(7L)).isEqualTo("po_order");
        assertThat(service.suggestTable(99L)).isNull();
    }
}
