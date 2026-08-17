package com.example.edgegateway.prototype;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** PROTOTYPE —— 跑一遍探测原型，打印结果，确认契约手感。 */
class StructureProbePrototypeTest {

    @Test
    void probesLocalMysqlTable() {
        assumeTrue(portOpen("localhost", 3306), "本地 MySQL 未启动，跳过");
        Map<String, Object> result = StructureProbePrototype.probeDb(
                "jdbc:mysql://localhost:3306/edge_gateway?useSSL=false&allowPublicKeyRetrieval=true",
                "gateway", "gateway", "po_order", 5);
        System.out.println("[PROTOTYPE] db probe: " + result);
        assertThat(result.get("source")).isEqualTo("db");
        assertThat((List<?>) result.get("fields")).isNotEmpty();
    }

    @Test
    void probesSampleFallback() {
        Map<String, Object> sample = Map.of(
                "order_no", "PO-2026-001",
                "data", Map.of("items", List.of(Map.of("name", "Elizer", "qty", 2))));
        Map<String, Object> result = StructureProbePrototype.probeSample(sample);
        System.out.println("[PROTOTYPE] sample probe: " + result);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
        assertThat(fields).extracting(f -> f.get("name"))
                .contains("order_no", "data.items[0].name", "data.items[0].qty");
    }

    private boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
