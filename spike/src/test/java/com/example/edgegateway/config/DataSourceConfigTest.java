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
    void buildsHttpConfigAndRejectsMissingMethod() {
        HttpDataSourceConfig config =
                (HttpDataSourceConfig) DataSourceConfig.from(DataSourceType.HTTP, Map.of("method", "GET"));
        assertThat(config.method()).isEqualTo("GET");
        assertThatCode(config::validate).doesNotThrowAnyException();

        assertThatThrownBy(() -> DataSourceConfig.from(DataSourceType.HTTP, Map.of()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("method");
    }

    @Test
    void mysqlConfigRejectsMissingUrl() {
        MysqlDataSourceConfig config = (MysqlDataSourceConfig) DataSourceConfig.from(
                DataSourceType.MYSQL, Map.of("username", "sa", "password", "x"));

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void rabbitConfigFillsDefaults() {
        RabbitMqDataSourceConfig config = (RabbitMqDataSourceConfig) DataSourceConfig.from(
                DataSourceType.RABBITMQ, Map.of("queue", "mes.to.edge"));

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(5672);
        assertThat(config.username()).isEqualTo("guest");
        assertThat(config.password()).isEqualTo("guest");
        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void deserializesAdditionPolymorphicallyByType() throws Exception {
        DataSourceRequest request = mapper.readValue("""
                {"name":"erp","siteId":1,"type":"MYSQL","address":"jdbc:mysql://localhost:3306/x",
                 "addition":{"url":"jdbc:mysql://localhost:3306/x","username":"sa","password":"p"}}
                """, DataSourceRequest.class);

        assertThat(request.addition()).isInstanceOf(MysqlDataSourceConfig.class);
        assertThat(((MysqlDataSourceConfig) request.addition()).username()).isEqualTo("sa");
    }

    @Test
    void rejectsUnknownAdditionKey() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"name":"erp","siteId":1,"type":"HTTP","address":"http://x","addition":{"method":"GET","timeout":"abc"}}
                """, DataSourceRequest.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void rejectsInvalidPortValue() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"name":"mq","siteId":1,"type":"RABBITMQ","address":"amqp://x",
                 "addition":{"queue":"q","port":"abc"}}
                """, DataSourceRequest.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("port");
    }
}
