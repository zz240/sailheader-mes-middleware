package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MQ 消费动作（南向入口，只能作链首）：数据源（broker）+ queue/exchange/routingKey。 */
public record MqConsumeConfig(
        Long dataSourceId,
        String queue,
        String exchange,
        String routingKey
) implements ActionConfig {

    public static MqConsumeConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "queue", "exchange", "routingKey"));
        Object id = map.get("dataSourceId");
        return new MqConsumeConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("queue")),
                ConfigValues.str(map.get("exchange")),
                ConfigValues.str(map.get("routingKey")));
    }

    @Override
    public ActionKind type() {
        return ActionKind.MQ_CONSUME;
    }

    @Override
    public void validate() {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("MQ 消费动作需要 dataSourceId");
        }
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("MQ 消费动作需要 queue");
        }
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataSourceId", dataSourceId);
        if (queue != null) {
            map.put("queue", queue);
        }
        if (exchange != null) {
            map.put("exchange", exchange);
        }
        if (routingKey != null) {
            map.put("routingKey", routingKey);
        }
        return map;
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
