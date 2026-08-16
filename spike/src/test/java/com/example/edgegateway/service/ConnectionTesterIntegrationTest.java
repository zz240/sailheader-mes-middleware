package com.example.edgegateway.service;

import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 连接测试的真实连通性验证（依赖本地 docker 的 MySQL / RabbitMQ）。
 * 本地服务未启动时，对应用例通过 assumeTrue 自动跳过，不把常规单测跑红。
 */
class ConnectionTesterIntegrationTest {

    private final ConnectionTester tester = new ConnectionTester();

    @Test
    void httpReachableReturnsOk() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            DataSource ds = new DataSource();
            ds.setType(DataSourceType.HTTP);
            ds.setAddress("http://127.0.0.1:" + server.getAddress().getPort() + "/ping");
            ds.setAddition(Map.of("method", "GET"));

            ConnectionTester.TestResult result = tester.test(ds);

            assertThat(result.ok()).isTrue();
            assertThat(result.message()).contains("HTTP 200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpUnreachableReturnsFail() {
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.HTTP);
        ds.setAddress("http://127.0.0.1:1/nothing"); // 端口 1 通常拒绝连接
        ds.setAddition(Map.of("method", "GET"));

        assertThat(tester.test(ds).ok()).isFalse();
    }

    @Test
    void mysqlReachableReturnsOk() {
        assumeTrue(portOpen("localhost", 3306), "本地 MySQL 未启动，跳过");
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition(Map.of(
                "url", "jdbc:mysql://localhost:3306/edge_gateway?useSSL=false&allowPublicKeyRetrieval=true",
                "username", "gateway",
                "password", "gateway"));

        assertThat(tester.test(ds).ok()).isTrue();
    }

    @Test
    void mysqlUnreachableReturnsFail() {
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition(Map.of(
                "url", "jdbc:mysql://localhost:33306/edge_gateway?connectTimeout=2000&socketTimeout=2000",
                "username", "gateway",
                "password", "gateway"));

        assertThat(tester.test(ds).ok()).isFalse();
    }

    @Test
    void rabbitMqReachableReturnsOk() {
        assumeTrue(portOpen("localhost", 5672), "本地 RabbitMQ 未启动，跳过");
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.RABBITMQ);
        ds.setAddition(Map.of(
                "queue", "mes.to.edge", "host", "localhost",
                "port", 5672, "username", "guest", "password", "guest"));

        assertThat(tester.test(ds).ok()).isTrue();
    }

    @Test
    void rabbitMqUnreachableReturnsFail() {
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.RABBITMQ);
        ds.setAddition(Map.of(
                "queue", "mes.to.edge", "host", "localhost",
                "port", 55672, "username", "guest", "password", "guest"));

        assertThat(tester.test(ds).ok()).isFalse();
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
