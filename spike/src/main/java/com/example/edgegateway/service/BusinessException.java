package com.example.edgegateway.service;

/**
 * 业务异常：携带业务错误码 + 自定义 message + 可选字段（供前端定位输入框）。
 */
public class BusinessException extends RuntimeException {

    private final BusinessCode code;
    private final String field;

    public BusinessException(BusinessCode code) {
        this(code, code.defaultMessage(), null);
    }

    public BusinessException(BusinessCode code, String message) {
        this(code, message, null);
    }

    public BusinessException(BusinessCode code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public static BusinessException of(BusinessCode code, String message) {
        return new BusinessException(code, message);
    }

    public BusinessCode businessCode() {
        return code;
    }

    public String field() {
        return field;
    }
}
