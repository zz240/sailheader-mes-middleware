package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MQ 发布动作：数据源（broker）+ queue/exchange/routingKey + bodySourceStep。 */
public record MqPublishConfig(
        Long dataSourceId,
        String queue,
        String exchange,
        String routingKey,
        Integer bodySourceStep
) implements ActionConfig {

    public static MqPublishConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "queue", "exchange", "routingKey", "bodySourceStep"));
        Object id = map.get("dataSourceId");
        return new MqPublishConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("queue")),
                ConfigValues.str(map.get("exchange")),
                ConfigValues.str(map.get("routingKey")),
                map.get("bodySourceStep") == null ? null : Integer.valueOf(map.get("bodySourceStep").toString()));
    }

    @Override
    public ActionKind type() {
        return ActionKind.MQ_PUBLISH;
    }

    @Override
    public void validate() {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("MQ 发布动作需要 dataSourceId");
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
        if (bodySourceStep != null) {
            map.put("bodySourceStep", bodySourceStep);
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
