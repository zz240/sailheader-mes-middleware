package com.example.edgegateway.config.action;

import com.example.edgegateway.model.ActionKind;

import java.util.Map;

/**
 * 动作配置的类型化包装：每种动作种类（DS-8）各有自己的配置结构与校验。
 * 与 DataSourceConfig 同一套模式：实体存 Map，服务边界转强类型。
 *
 * <p>{@code ONE_TO_MANY}（一分多）的两档见 {@link OneToManyConfig.Mode}，配置类同一个。</p>
 */
public sealed interface ActionConfig
        permits MqConsumeConfig, ReadSqlConfig, FieldMappingConfig,
        WriteUpsertConfig, HttpCallConfig, MqPublishConfig, OneToManyConfig, AggregateConfig {

    ActionKind type();

    void validate();

    /** 转回 Map 供动作实体落库（不含 null 值）。 */
    Map<String, Object> toMap();

    static ActionConfig from(ActionKind kind, Map<String, Object> addition) {
        Map<String, Object> map = addition == null ? Map.of() : addition;
        return switch (kind) {
            case MQ_CONSUME -> MqConsumeConfig.from(map);
            case READ_SQL -> ReadSqlConfig.from(map);
            case FIELD_MAP -> FieldMappingConfig.from(map);
            case WRITE_UPSERT -> WriteUpsertConfig.from(map);
            case HTTP_CALL -> HttpCallConfig.from(map);
            case MQ_PUBLISH -> MqPublishConfig.from(map);
            case ONE_TO_MANY -> OneToManyConfig.from(map);
            case AGGREGATE -> AggregateConfig.from(map);
        };
    }
}
