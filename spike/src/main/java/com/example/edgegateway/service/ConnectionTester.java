package com.example.edgegateway.service;

import com.example.edgegateway.config.DataSourceConfig;
import com.example.edgegateway.config.HttpDataSourceConfig;
import com.example.edgegateway.config.JdbcDataSourceConfig;
import com.example.edgegateway.config.RabbitMqDataSourceConfig;
import com.example.edgegateway.model.DataSource;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;

/**
 * 连接测试：按数据源类型做一次轻量探测（HTTP 请求 / JDBC 连接 / RabbitMQ 建连）。
 * 配置先转成类型化 DataSourceConfig，再按类型执行；任何异常都收敛为 ok=false。
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
            DataSourceConfig config =
                    DataSourceConfig.from(dataSource.getType(), dataSource.getAddition());
            return switch (config) {
                case HttpDataSourceConfig c -> testHttp(dataSource.getAddress(), c);
                case JdbcDataSourceConfig c -> testJdbc(dataSource.getAddress(), c.username(), c.password());
                case RabbitMqDataSourceConfig c -> testRabbitMq(c);
            };
        } catch (Exception e) {
            return new TestResult(false, e.getMessage());
        }
    }

    private TestResult testHttp(String address, HttpDataSourceConfig config) throws Exception {
        // HTTP：访问目标地址，3 秒超时；配置了 token 时带 Authorization: Bearer 头；401 视为鉴权失败
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(address))
                .timeout(Duration.ofSeconds(3))
                .method("GET", HttpRequest.BodyPublishers.noBody());
        if (config.token() != null && !config.token().isBlank()) {
            builder.header("Authorization", "Bearer " + config.token());
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            return new TestResult(false, "HTTP 401 鉴权失败，请检查 token");
        }
        return new TestResult(true, "HTTP " + response.statusCode());
    }

    private TestResult testJdbc(String url, String username, String password) throws Exception {
        // 关系库：url 取数据源 address，username/password 取类型化配置；MySQL 与 SQL Server 是同一套机制
        DriverManager.setLoginTimeout(3);
        try (var connection = DriverManager.getConnection(url, username, password)) {
            return new TestResult(true, "数据库连接成功");
        }
    }

    private TestResult testRabbitMq(RabbitMqDataSourceConfig config) throws Exception {
        // RabbitMQ：用 amqp-client 建连，缺省值已在 RabbitMqDataSourceConfig.from 中补齐
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.host());
        factory.setPort(config.port());
        factory.setUsername(config.username());
        factory.setPassword(config.password());
        try (var connection = factory.newConnection()) {
            return new TestResult(true, "MQ 连接成功");
        }
    }
}
