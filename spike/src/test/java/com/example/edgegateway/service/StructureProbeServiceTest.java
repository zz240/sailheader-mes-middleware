package com.example.edgegateway.service;

import com.example.edgegateway.mapping.suggest.SourceField;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * 缓存按「数据源 + 表」为键。用一个假连库实现数「连了几次库」：
     * 同一张表第二次必须走缓存，换一张表必须重新连。
     * （只按数据源缓存时，t_b 会拿到 t_a 的字段——这个用例就是冲着那次修复来的。）
     */
    @Test
    void probeCacheIsScopedPerTableNotPerDataSource() {
        AtomicInteger dials = new AtomicInteger();
        StructureProbeService cached = new StructureProbeService(
                actionMapper, dataSourceMapper, (ds, table) -> {
                    dials.incrementAndGet();
                    return new SourceStructure("db:" + table,
                            List.of(new SourceField(table + "_col", "VARCHAR", true, List.of())));
                });

        DataSource ds = new DataSource();
        ds.setId(9L);
        ds.setType(DataSourceType.MYSQL);

        assertThat(cached.probeFromDb(ds, "t_a", false).key()).isEqualTo("db:t_a");
        assertThat(cached.probeFromDb(ds, "t_a", false).key()).isEqualTo("db:t_a");
        assertThat(cached.probeFromDb(ds, "t_b", false).key()).isEqualTo("db:t_b");
        assertThat(cached.probeFromDb(ds, "t_a", false).fields())
                .extracting(SourceField::name).containsExactly("t_a_col");

        // t_a 连一次（第二次走缓存），t_b 是另一张表必须再连一次。
        assertThat(dials.get()).isEqualTo(2);
    }

    /** 表名没给时必须当场报错，不能拿缓存里别的表糊弄过去。 */
    @Test
    void probeRejectsMissingTableName() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setType(DataSourceType.MYSQL);

        assertThatThrownBy(() -> service.probeFromDb(ds, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名不合法");
    }
}
