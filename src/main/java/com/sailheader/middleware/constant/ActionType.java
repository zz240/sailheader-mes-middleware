package com.sailheader.middleware.constant;

import lombok.Getter;

public enum ActionType {
    remoteAccess((short)1 ,"rest.remoteAccess");

    @Getter
    private final Short type;

    @Getter
    private final String beanName;

    ActionType(Short type, String beanName) {
        this.type = type;
        this.beanName = beanName;
    }
}
