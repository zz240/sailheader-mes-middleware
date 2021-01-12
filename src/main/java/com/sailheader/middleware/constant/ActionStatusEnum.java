package com.sailheader.middleware.constant;

import lombok.Getter;

public enum ActionStatusEnum {
    INITIAL(0, "初始化"),
    SUCCESS(1, "成功"),
    FAILURE(2, "失败");

    @Getter
    private final int status;

    @Getter
    private final String remark;

    ActionStatusEnum(int status, String remark) {
        this.status = status;
        this.remark = remark;
    }


}
