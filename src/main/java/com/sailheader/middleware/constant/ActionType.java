package com.sailheader.middleware.constant;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public enum ActionType {
    // 远程访问
    REMOTE_ACCESS((short)1 ,"action.remote.access"),
    // 字段映射 - JSONObject
    MAPPING_OBJECT((short)2,"action.field.mapping.object"),
    // 字段映射 - JSONArray
    MAPPING_ARRAY((short)3,"action.field.mapping.array");

    @Getter
    private final Short type;

    @Getter
    private final String beanName;

    ActionType(Short type, String beanName) {
        this.type = type;
        this.beanName = beanName;
    }

    public static final Map<Short,ActionType> actionTypeById = getEnumById();

    private static Map<Short, ActionType> getEnumById() {
        Map<Short,ActionType> result = new HashMap<>();
        for (ActionType value : ActionType.values()) {
            result.put(value.type, value);
        }
        return result;
    }
}
