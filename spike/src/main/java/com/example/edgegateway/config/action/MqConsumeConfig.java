package com.example.edgegateway.config.action;

import com.example.edgegateway.config.ConfigValues;
import com.example.edgegateway.model.ActionKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MQ 消费动作（南向入口，只能作链首）：数据源（broker）+ queue/exchange/exchangeType/routingKey + 业务键 JSON 路径（#70）。 */
public record MqConsumeConfig(
        Long dataSourceId,
        String queue,
        String exchange,
        String exchangeType,
        String routingKey,
        String businessKey
) implements ActionConfig {

    /** ADR-0010：exchange 类型枚举（与 Camel spring-rabbitmq exchangeType 一致，缺省 direct）。 */
    public static final List<String> EXCHANGE_TYPES = List.of("direct", "topic", "fanout", "headers");

    public static MqConsumeConfig from(Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        rejectUnknownKeys(map, Set.of("dataSourceId", "queue", "exchange", "exchangeType", "routingKey", "businessKey"));
        Object id = map.get("dataSourceId");
        return new MqConsumeConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(map.get("queue")),
                ConfigValues.str(map.get("exchange")),
                ConfigValues.str(map.get("exchangeType")),
                ConfigValues.str(map.get("routingKey")),
                ConfigValues.str(map.get("businessKey")));
    }

    /** 实际生效的 exchange 类型：未配置/空白时缺省 direct（ADR-0010，存量任务零影响）。 */
    public String effectiveExchangeType() {
        return exchangeType == null || exchangeType.isBlank() ? "direct" : exchangeType;
    }

    /** 实际生效的 exchange 名：未配置/空白时回退数据源名（ADR-0010 缺省兼容）。 */
    public String effectiveExchange(String fallback) {
        return exchange == null || exchange.isBlank() ? fallback : exchange;
    }

    /** 实际生效的 binding key：未配置/空白时回退 queue（ADR-0010 缺省兼容）。 */
    public String effectiveRoutingKey(String fallbackQueue) {
        return routingKey == null || routingKey.isBlank() ? fallbackQueue : routingKey;
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
        if (exchangeType != null && !EXCHANGE_TYPES.contains(exchangeType)) {
            throw new IllegalArgumentException(
                    "exchangeType 应为 " + String.join("/", EXCHANGE_TYPES) + ": " + exchangeType);
        }
        if (businessKey != null && !businessKey.isBlank()
                && !businessKey.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")) {
            throw new IllegalArgumentException("业务键应为点分 JSON 路径（如 data.orderNo，禁止空段/非法字符）: " + businessKey);
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
        if (exchangeType != null) {
            map.put("exchangeType", exchangeType);
        }
        if (routingKey != null) {
            map.put("routingKey", routingKey);
        }
        if (businessKey != null) {
            map.put("businessKey", businessKey);
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
