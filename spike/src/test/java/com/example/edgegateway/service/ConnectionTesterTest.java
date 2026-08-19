package com.example.edgegateway.service;

import com.example.edgegateway.model.DataSource;
import com.example.edgegateway.model.DataSourceType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTesterTest {

    private final ConnectionTester tester = new ConnectionTester();

    @Test
    void unknownTypeReturnsFail() {
        DataSource ds = new DataSource();
        ds.setType(null);

        assertThat(tester.test(ds).ok()).isFalse();
    }

    @Test
    void invalidAdditionReturnsFail() {
        DataSource ds = new DataSource();
        ds.setType(DataSourceType.MYSQL);
        ds.setAddition(Map.of()); // 缺 url/username/password，连接必然失败

        assertThat(tester.test(ds).ok()).isFalse();
    }

    @Test
    void httpSendsBearerTokenWhenConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] captured = {null};
        server.createContext("/token", exchange -> {
            captured[0] = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            DataSource ds = new DataSource();
            ds.setType(DataSourceType.HTTP);
            ds.setAddress("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
            ds.setAddition(Map.of("token", "abc123"));

            ConnectionTester.TestResult result = tester.test(ds);

            assertThat(result.ok()).isTrue();
            assertThat(captured[0]).isEqualTo("Bearer abc123");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void http401ReportsAuthFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/deny", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            DataSource ds = new DataSource();
            ds.setType(DataSourceType.HTTP);
            ds.setAddress("http://127.0.0.1:" + server.getAddress().getPort() + "/deny");
            ds.setAddition(Map.of("token", "bad"));

            ConnectionTester.TestResult result = tester.test(ds);

            assertThat(result.ok()).isFalse();
            assertThat(result.message()).contains("鉴权失败");
        } finally {
            server.stop(0);
        }
    }
}
