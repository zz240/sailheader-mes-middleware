package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;

import java.util.Map;

/** RabbitMQ 数据源配置：queue 必填；host/port/账号密码有默认值。 */
public record RabbitMqDataSourceConfig(
        String queue,
        String host,
        int port,
        String username,
        String password) implements DataSourceConfig {

    public static RabbitMqDataSourceConfig from(Map<String, Object> addition) {
        return new RabbitMqDataSourceConfig(
                ConfigValues.str(addition.get("queue")),
                ConfigValues.str(addition.getOrDefault("host", "localhost")),
                ConfigValues.intValue(addition.getOrDefault("port", 5672), 5672),
                ConfigValues.str(addition.getOrDefault("username", "guest")),
                ConfigValues.str(addition.getOrDefault("password", "guest")));
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.RABBITMQ;
    }

    @Override
    public void validate() {
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("RabbitMQ 数据源需要 queue");
        }
    }
}
