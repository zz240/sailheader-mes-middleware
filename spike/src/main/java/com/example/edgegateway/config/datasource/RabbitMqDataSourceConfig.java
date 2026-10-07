package com.example.edgegateway.config.datasource;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.DataSourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * RabbitMQ 数据源配置（连接级，ADR-0007）：broker 在 address，队列 / exchange / routingKey 在动作里配；
 * host/port/账号密码有默认值。
 */
public record RabbitMqDataSourceConfig(
        String host,
        int port,
        String username,
        String password) implements DataSourceConfig {

    public static RabbitMqDataSourceConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("host", "port", "username", "password"));
        return new RabbitMqDataSourceConfig(
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
        // 连接级：broker 在 address，凭证可选（有默认值），无必填字段。
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
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
