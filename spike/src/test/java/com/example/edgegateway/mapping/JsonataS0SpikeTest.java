package com.example.edgegateway.mapping;

import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.Jsonata.JFunction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DS-10.2 S0 spike：验证 JSONata（dashjoin/jsonata-java 0.9.10）能否覆盖 BOM 场景。
 * 覆盖点：数组迭代（$map 与 .{} 简写）、行号（$i）、CONCAT（&）、枚举（$lookup）、
 * INT（$number）、DATE 自定义函数（registerFunction + JFunction 签名）。
 * 数据源：src/test/resources/json/erp测试数据.json（真实 ERP BOM 样例）。
 */
class JsonataS0SpikeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 读取 ERP 样例并返回 erpBom 对象，作为表达式求值根。 */
    private Map<String, Object> erpBom() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/json/erp测试数据.json")) {
            Map<String, Object> payload = objectMapper.readValue(in, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> bom = (Map<String, Object>) payload.get("erpBom");
            return bom;
        }
    }

    /** 完整 BOM 表达式：对应 mapping-framework.md §4.3 的规则树手写编译结果。 */
    @Test
    void fullBomExpressionProducesExpectedStructure() throws Exception {
        String expr = """
                {
                  "bom": {
                    "bomNo": "BOM-" & productCode,
                    "productCode": productCode,
                    "productName": productName,
                    "bomLines": $map(items, function($v, $i) {
                      {
                        "lineNo": $i + 1,
                        "materialCode": $v.materialCode,
                        "materialName": $v.materialName,
                        "qty": $number($v.quantity),
                        "unit": $lookup({"PCS": "件", "SET": "套"}, $v.unit)
                      }
                    })
                  }
                }
                """;

        Object result = Jsonata.jsonata(expr).evaluate(erpBom());

        @SuppressWarnings("unchecked")
        Map<String, Object> bom = (Map<String, Object>) ((Map<String, Object>) result).get("bom");
        assertThat(bom.get("bomNo")).isEqualTo("BOM-PRD-BUMPER-001");
        assertThat(bom.get("productCode")).isEqualTo("PRD-BUMPER-001");
        assertThat(bom.get("productName")).isEqualTo("前保险杠");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) bom.get("bomLines");
        assertThat(lines).hasSize(2);

        Map<String, Object> first = lines.get(0);
        assertThat(first.get("lineNo")).isEqualTo(1);
        assertThat(first.get("materialCode")).isEqualTo("MAT-718H");
        assertThat(first.get("materialName")).isEqualTo("718H模具钢");
        assertThat(((Number) first.get("qty")).intValue()).isEqualTo(1);
        assertThat(first.get("unit")).isEqualTo("件");

        Map<String, Object> second = lines.get(1);
        assertThat(second.get("lineNo")).isEqualTo(2);
        assertThat(second.get("materialName")).isEqualTo("SKD61");
        assertThat(((Number) second.get("qty")).intValue()).isEqualTo(24);
        assertThat(second.get("unit")).isEqualTo("件");
    }

    /**
     * 数组迭代的 .{} 简写语法：字段映射可用，但注意 dashjoin 0.9.10 在该简写上下文里
     * 没有 $i（求值为 null）——行号必须走 $map 的 $i 参数，编译器统一用 $map 生成。
     */
    @Test
    void objectConstructionShorthandOverArrayAlsoWorks() throws Exception {
        String expr = """
                items.{
                  "materialCode": materialCode,
                  "unit": $lookup({"PCS": "件", "SET": "套"}, unit)
                }
                """;

        Object result = Jsonata.jsonata(expr).evaluate(erpBom());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) result;
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("materialCode")).isEqualTo("MAT-718H");
        assertThat(lines.get(0).get("unit")).isEqualTo("件");
        assertThat(lines.get(1).get("materialCode")).isEqualTo("MAT-SKD61");
    }

    /** DATE 自定义函数：ISO-8601 时间戳按目标格式输出，可在对象构造中作为叶子使用。 */
    @Test
    void dateCustomFunctionFormatsIsoTimestamp() throws Exception {
        String expr = """
                {
                  "createdDate": $dateFormat(createdTime, "yyyy-MM-dd"),
                  "createdTime": $dateFormat(createdTime, "yyyy-MM-dd HH:mm:ss"),
                  "effectiveDate": $dateFormat(effectiveDate, "yyyy/MM/dd")
                }
                """;
        Jsonata expression = Jsonata.jsonata(expr);
        expression.registerFunction("dateFormat",
                new JFunction((input, args) -> formatDate(args.get(0), (String) args.get(1)), "<ss:s>"));

        Object result = expression.evaluate(erpBom());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result;
        assertThat(out.get("createdDate")).isEqualTo("2026-08-21");
        assertThat(out.get("createdTime")).isEqualTo("2026-08-21 10:00:00");
        assertThat(out.get("effectiveDate")).isEqualTo("2026/08/21");
    }

    private String formatDate(Object value, String pattern) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        String raw = String.valueOf(value);
        try {
            return OffsetDateTime.parse(raw).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Instant.parse(raw).atZone(ZoneOffset.UTC).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(raw).format(formatter);
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(raw).format(formatter);
        } catch (Exception ignored) {
            return raw;
        }
    }
}
