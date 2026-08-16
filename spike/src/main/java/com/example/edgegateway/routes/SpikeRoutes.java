package com.example.edgegateway.routes;

import com.example.edgegateway.WatermarkStore;
import com.example.edgegateway.mapping.FieldMapper;
import com.example.edgegateway.mapping.MappingRule;
import com.example.edgegateway.mapping.TransformType;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SpikeRoutes extends RouteBuilder {

    private final WatermarkStore watermark;
    private final JdbcTemplate jdbc;

    public SpikeRoutes(WatermarkStore watermark, JdbcTemplate jdbc) {
        this.watermark = watermark;
        this.jdbc = jdbc;
    }

    private final FieldMapper northMapper = new FieldMapper(List.of(
            new MappingRule("order_no", "sheet_no", TransformType.STRING, null, null, null),
            new MappingRule("vendor_code", "vendor_code", TransformType.STRING, null, null, null),
            new MappingRule("order_date", "sheet_date", TransformType.DATE, null, null, "yyyy-MM-dd"),
            new MappingRule("status", "status", TransformType.ENUM, null, Map.of("ACTIVE", 1), null)
    ));

    private final FieldMapper southMapper = new FieldMapper(List.of(
            new MappingRule("orderNo", "order_no", TransformType.STRING, null, null, null),
            new MappingRule("material", "material", TransformType.STRING, null, null, null),
            new MappingRule("qty", "qty", TransformType.INT, null, null, null)
    ));

    @Override
    public void configure() {
        from("timer:po-sync?period=30000&delay=5000")
                .routeId("north-purchase-sync")
                .process(exchange -> {
                    long lastId = watermark.get("po_order");
                    exchange.getMessage().setHeader("lastId", lastId);
                })
                .to("sql:select id, order_no, vendor_code, order_date, status "
                        + "from po_order where id > :#lastId order by id limit 100"
                        + "?dataSource=#dataSource&outputType=SelectList")
                .process(exchange -> {
                    List<Map<String, Object>> rows = exchange.getMessage().getBody(List.class);
                    long maxId = exchange.getMessage().getHeader("lastId", Long.class);
                    for (Map<String, Object> row : rows) {
                        long id = ((Number) row.get("id")).longValue();
                        maxId = Math.max(maxId, id);
                    }
                    exchange.setProperty("maxId", maxId);
                })
                .split(body())
                .process(exchange -> {
                    Map<String, Object> src = exchange.getMessage().getBody(Map.class);
                    exchange.getMessage().setBody(northMapper.apply(src));
                })
                .marshal().json()
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .setHeader("Authorization", constant("Bearer mock-token"))
                .to("http://localhost:8080/mock/mes/purchase-order")
                .end()
                .process(exchange -> {
                    Long maxId = exchange.getProperty("maxId", Long.class);
                    if (maxId != null) {
                        watermark.put("po_order", maxId);
                    }
                });

        from("spring-rabbitmq:mes.exchange?queues=mes.to.edge"
                + "&routingKey=mes.to.edge&autoDeclare=true")
                .routeId("south-mes-to-edge")
                .unmarshal().json()
                .process(exchange -> {
                    Map<String, Object> src = exchange.getMessage().getBody(Map.class);
                    exchange.getMessage().setBody(southMapper.apply(src));
                })
                .process(exchange -> {
                    Map<String, Object> row = exchange.getMessage().getBody(Map.class);
                    jdbc.update(
                            "insert into po_receipt(order_no, material, qty) values (?, ?, ?) "
                                    + "on duplicate key update material = values(material), qty = values(qty)",
                            row.get("order_no"), row.get("material"), row.get("qty"));
                })
                .log("south upserted: ${body}");
    }
}
