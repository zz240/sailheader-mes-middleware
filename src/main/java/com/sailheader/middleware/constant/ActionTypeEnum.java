package com.sailheader.middleware.constant;

import lombok.Getter;

public enum ActionTypeEnum {
    remoteAccess((short)1 ,"action.remote.access");//远程访问

    @Getter
    private final Short type;

    @Getter
    private final String beanName;

    ActionTypeEnum(Short type, String beanName) {
        this.type = type;
        this.beanName = beanName;
    }
}
