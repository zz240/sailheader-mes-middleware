package com.example.edgegateway.mapping;

import com.example.edgegateway.config.FieldMappingConfig;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldMapperTest {

    @Test
    void mapsNorthboundRow() {
        FieldMapper mapper = new FieldMapper(List.of(
                new MappingRule("order_no", "sheet_no", TransformType.STRING, null, null, null),
                new MappingRule("vendor_code", "vendor_code", TransformType.STRING, null, null, null),
                new MappingRule("order_date", "sheet_date", TransformType.DATE, null, null, "yyyy-MM-dd"),
                new MappingRule("status", "status", TransformType.ENUM, null, Map.of("ACTIVE", 1), null)
        ));

        Map<String, Object> source = Map.of(
                "order_no", "PO-2026-001",
                "vendor_code", "V001",
                "order_date", Timestamp.valueOf("2026-08-14 10:00:00"),
                "status", "ACTIVE"
        );

        Map<String, Object> out = mapper.apply(source);

        assertEquals("PO-2026-001", out.get("sheet_no"));
        assertEquals("V001", out.get("vendor_code"));
        assertEquals("2026-08-14", out.get("sheet_date"));
        assertEquals(1, out.get("status"));
    }

    @Test
    void mapsConstantAndInt() {
        FieldMapper mapper = new FieldMapper(List.of(
                new MappingRule(null, "source", TransformType.CONSTANT, "MES", null, null),
                new MappingRule("qty", "qty", TransformType.INT, null, null, null)
        ));

        Map<String, Object> out = mapper.apply(Map.of("qty", "10"));

        assertEquals("MES", out.get("source"));
        assertEquals(10, out.get("qty"));
    }

    @Test
    void resolvesArrayIndexPath() {
        FieldMapper mapper = new FieldMapper(List.of(
                new MappingRule("data.items[0].name", "firstName", TransformType.STRING, null, null, null)));

        Map<String, Object> source = Map.of(
                "data", Map.of("items", List.of(Map.of("name", "Elizer"))));

        Map<String, Object> out = mapper.apply(source);

        assertEquals("Elizer", out.get("firstName"));
    }

    @Test
    void appliesConfigRules() {
        FieldMappingConfig config = FieldMappingConfig.from(Map.of("rules", List.of(
                Map.of("source", "order_no", "target", "sheet_no"),
                Map.of("source", "status", "target", "status",
                        "transform", "ENUM", "enumMap", Map.of("ACTIVE", 1)))));
        FieldMapper mapper = new FieldMapper(List.of());

        Map<String, Object> out = mapper.apply(
                Map.of(0, (Object) Map.of("order_no", "PO-1", "status", "ACTIVE")), config.rules());

        assertEquals("PO-1", out.get("sheet_no"));
        assertEquals(1, out.get("status"));
    }
}
