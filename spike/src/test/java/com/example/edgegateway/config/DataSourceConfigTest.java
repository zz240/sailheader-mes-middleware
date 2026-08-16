package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceConfigTest {

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
}
