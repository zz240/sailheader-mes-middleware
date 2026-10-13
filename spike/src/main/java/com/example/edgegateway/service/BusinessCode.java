package com.example.edgegateway.service;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码注册表：code 稳定不变（供前端 switch 分支），message 可随文案调整。
 * 粒度到"前端需要分支的场景"（如重名、任务未启用），不到 message 粒度。
 */
public enum BusinessCode {

    OK("OK", "ok", HttpStatus.OK),
    PARAM_INVALID("PARAM_INVALID", "参数错误", HttpStatus.BAD_REQUEST),
    JOB_NOT_ENABLED("JOB_NOT_ENABLED", "任务未启用", HttpStatus.BAD_REQUEST),
    JOB_ALREADY_RUNNING("JOB_ALREADY_RUNNING", "任务正在执行，请稍后再试", HttpStatus.CONFLICT),
    UNAUTHORIZED("UNAUTHORIZED", "API Key 缺失或无效", HttpStatus.UNAUTHORIZED),
    NOT_FOUND("NOT_FOUND", "不存在", HttpStatus.NOT_FOUND),
    CONFLICT("CONFLICT", "冲突", HttpStatus.CONFLICT),
    DUPLICATE_NAME("DUPLICATE_NAME", "名称已存在", HttpStatus.CONFLICT),
    INTERNAL_ERROR("INTERNAL_ERROR", "内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    BusinessCode(String code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
