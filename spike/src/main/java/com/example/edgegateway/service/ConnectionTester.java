package com.example.edgegateway.service;

import com.example.edgegateway.model.DataSource;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

/**
 * 连接测试：按数据源类型做一次轻量探测（HTTP 请求 / JDBC 连接 / RabbitMQ 建连），
 * 返回 {ok, message}；任何异常都收敛为 ok=false，不让异常冒泡到接口层。
 */
@Component
public class ConnectionTester {

    public record TestResult(boolean ok, String message) {
    }

    public TestResult test(DataSource dataSource) {
        if (dataSource == null || dataSource.getType() == null) {
            return new TestResult(false, "数据源类型缺失");
        }
        try {
            return switch (dataSource.getType()) {
                case HTTP -> testHttp(dataSource);
                case MYSQL -> testMysql(dataSource);
                case RABBITMQ -> testRabbitMq(dataSource);
            };
        } catch (Exception e) {
            return new TestResult(false, e.getMessage());
        }
    }

    private TestResult testHttp(DataSource dataSource) throws Exception {
        // HTTP：GET 目标地址，3 秒超时；能拿到任何响应码都算连通
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(dataSource.getAddress()))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new TestResult(true, "HTTP " + response.statusCode());
    }

    private TestResult testMysql(DataSource dataSource) throws Exception {
        // MySQL：用 addition 里的 url/username/password 建立 JDBC 连接
        Map<String, Object> addition = parse(dataSource.getAddition());
        String url = String.valueOf(addition.get("url"));
        String username = String.valueOf(addition.get("username"));
        String password = String.valueOf(addition.get("password"));
        DriverManager.setLoginTimeout(3);
        try (var connection = DriverManager.getConnection(url, username, password)) {
            return new TestResult(true, "数据库连接成功");
        }
    }

    private TestResult testRabbitMq(DataSource dataSource) throws Exception {
        // RabbitMQ：用 amqp-client 建连，host/port/账号密码缺省取 localhost/5672/guest/guest
        Map<String, Object> addition = parse(dataSource.getAddition());
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(String.valueOf(addition.getOrDefault("host", "localhost")));
        factory.setPort(Integer.parseInt(String.valueOf(addition.getOrDefault("port", "5672"))));
        factory.setUsername(String.valueOf(addition.getOrDefault("username", "guest")));
        factory.setPassword(String.valueOf(addition.getOrDefault("password", "guest")));
        try (var connection = factory.newConnection()) {
            return new TestResult(true, "MQ 连接成功");
        }
    }

    private Map<String, Object> parse(Map<String, Object> addition) {
        return addition == null ? Map.of() : addition;
    }
}
