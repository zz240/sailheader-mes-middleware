package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionType;

import java.util.Map;

/** 远程访问动作配置：可引用数据源（dataSourceId）或自带 SQL/地址。 */
public record RemoteAccessConfig(Long dataSourceId, String sql) implements ActionConfig {

    public static RemoteAccessConfig from(Map<String, Object> addition) {
        Object id = addition.get("dataSourceId");
        return new RemoteAccessConfig(
                id == null ? null : Long.valueOf(id.toString()),
                ConfigValues.str(addition.get("sql")));
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
