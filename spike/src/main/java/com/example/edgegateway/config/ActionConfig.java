package com.example.edgegateway.config;

import com.example.edgegateway.model.ActionType;

import java.util.Map;

/**
 * 动作配置的类型化包装：远程访问和字段映射各有自己的配置结构与校验。
 * 与 DataSourceConfig 同一套模式：实体存 Map，服务边界转强类型。
 */
public sealed interface ActionConfig permits RemoteAccessConfig, FieldMappingConfig {

    ActionType type();

    void validate();

    static ActionConfig from(ActionType type, Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        return switch (type) {
            case REMOTE_ACCESS -> RemoteAccessConfig.from(map);
            case FIELD_MAPPING -> FieldMappingConfig.from(map);
        };
    }
}
