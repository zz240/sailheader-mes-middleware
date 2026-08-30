package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;
import com.example.edgegateway.web.dto.DataSourceRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsHttpConfigWithToken() {
        HttpDataSourceConfig config =
                (HttpDataSourceConfig) DataSourceConfig.from(DataSourceType.HTTP, Map.of("token", "abc"));
        assertThat(config.token()).isEqualTo("abc");
        assertThatCode(config::validate).doesNotThrowAnyException();

        assertThatThrownBy(() -> DataSourceConfig.from(DataSourceType.HTTP, Map.of("method", "GET")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void mysqlConfigRejectsMissingPassword() {
        MysqlDataSourceConfig config = (MysqlDataSourceConfig) DataSourceConfig.from(
                DataSourceType.MYSQL, Map.of("username", "sa"));

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void mysqlConfigRejectsUrlKey() {
        // ADR-0007 连接级：url 归属数据源 address，addition 不应再有 url。
        assertThatThrownBy(() -> DataSourceConfig.from(DataSourceType.MYSQL,
                Map.of("url", "jdbc:mysql://localhost:3306/x", "username", "sa", "password", "p")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void rabbitConfigFillsDefaults() {
        RabbitMqDataSourceConfig config = (RabbitMqDataSourceConfig) DataSourceConfig.from(
                DataSourceType.RABBITMQ, Map.of());

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(5672);
        assertThat(config.username()).isEqualTo("guest");
        assertThat(config.password()).isEqualTo("guest");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void rabbitConfigRejectsQueueKey() {
        // ADR-0007：队列属于动作，数据源为连接级，不应再有 queue 字段。
        assertThatThrownBy(() -> DataSourceConfig.from(DataSourceType.RABBITMQ, Map.of("queue", "mes.to.edge")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    void deserializesAdditionPolymorphicallyByType() throws Exception {
        DataSourceRequest request = mapper.readValue("""
                {"name":"erp","siteId":1,"type":"MYSQL","address":"jdbc:mysql://localhost:3306/x",
                 "addition":{"username":"sa","password":"p"}}
                """, DataSourceRequest.class);

        assertThat(request.addition()).isInstanceOf(MysqlDataSourceConfig.class);
        assertThat(((MysqlDataSourceConfig) request.addition()).username()).isEqualTo("sa");
    }

    @Test
    void rejectsUnknownAdditionKey() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"name":"erp","siteId":1,"type":"HTTP","address":"http://x","addition":{"token":"t","timeout":"abc"}}
                """, DataSourceRequest.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void rejectsInvalidPortValue() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"name":"mq","siteId":1,"type":"RABBITMQ","address":"amqp://x",
                 "addition":{"port":"abc"}}
                """, DataSourceRequest.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("port");
    }
}
