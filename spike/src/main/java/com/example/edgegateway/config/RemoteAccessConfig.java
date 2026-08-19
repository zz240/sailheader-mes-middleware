package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionType;

import java.util.Map;

/**
 * 远程访问动作配置（ADR-0007 连接级统一后）：数据源 = 连接实例，
 * 资源归动作——HTTP 写带 path/method、MQ 消费带 queue/exchange/routingKey、MySQL 读带 sql。
 */
public record RemoteAccessConfig(
        Long dataSourceId,
        String sql,
        String path,
        String method,
        String queue,
        String exchange,
        String routingKey
) implements ActionConfig {

    public static RemoteAccessConfig from(Map<String, Object> addition) {
        Object id = addition.get("dataSourceId");
        return new RemoteAccessConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(addition.get("sql")),
                ConfigValues.str(addition.get("path")),
                ConfigValues.str(addition.get("method")),
                ConfigValues.str(addition.get("queue")),
                ConfigValues.str(addition.get("exchange")),
                ConfigValues.str(addition.get("routingKey")));
    }

    @Override
    public ActionType type() {
        return ActionType.REMOTE_ACCESS;
    }

    @Override
    public void validate() {
        // 无必填项：HTTP 可直接给地址，DB 走数据源引用
    }
}
