package com.example.edgegateway.config;

import com.example.edgegateway.model.DataSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** RabbitMQ 数据源配置：queue 必填；host/port/账号密码有默认值。 */
public record RabbitMqDataSourceConfig(
        String queue,
        String host,
        int port,
        String username,
        String password) implements DataSourceConfig {

    public static RabbitMqDataSourceConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("queue", "host", "port", "username", "password"));
        return new RabbitMqDataSourceConfig(
                ConfigValues.str(map.get("queue")),
                ConfigValues.str(map.getOrDefault("host", "localhost")),
                portValue(map.get("port")),
                ConfigValues.str(map.getOrDefault("username", "guest")),
                ConfigValues.str(map.getOrDefault("password", "guest")));
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

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("queue", queue);
        map.put("host", host);
        map.put("port", port);
        map.put("username", username);
        map.put("password", password);
        return map;
    }

    private static int portValue(Object value) {
        if (value == null) {
            return 5672;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port 必须是整数: " + value);
        }
    }

    private static void rejectUnknownKeys(Map<String, Object> map, Set<String> allowed) {
        map.keySet().stream()
                .filter(k -> !allowed.contains(k))
                .findFirst()
                .ifPresent(k -> {
                    throw new IllegalArgumentException("未知字段: " + k);
                });
    }
}
