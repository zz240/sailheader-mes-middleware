package com.sailheader.middleware.common.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class ConditionVo implements Serializable {

    private static final long serialVersionUID = -5099378457111419832L;
    /**
     * 数据库字段名
     */
    private String name;
    /**
     * 连接类型，如：CONTAINSTEXT，EQUALS
     */
    private String conditionalOperators;
    /**
     * 字段值
     */
    private String value;
}
