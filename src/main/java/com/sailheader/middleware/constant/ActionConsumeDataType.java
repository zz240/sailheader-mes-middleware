package com.sailheader.middleware.constant;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 消费数据类型
 */
public enum ActionConsumeDataType {
    DIRECT(1 ,"action.remote.direct"),
    COMPLETION(2, "action.consume.completion");

    @Getter
    private final int type;

    @Getter
    private final String beanName;

    ActionConsumeDataType(int type, String beanName) {
        this.type = type;
        this.beanName = beanName;
    }

    public static final Map<Integer, ActionConsumeDataType> actionConsumeDataTypeById = getEnumById();

    private static Map<Integer, ActionConsumeDataType> getEnumById() {
        Map<Integer, ActionConsumeDataType> result = new HashMap<>();
        for (ActionConsumeDataType value : ActionConsumeDataType.values()) {
            result.put(value.type, value);
        }
        return result;
    }
}
