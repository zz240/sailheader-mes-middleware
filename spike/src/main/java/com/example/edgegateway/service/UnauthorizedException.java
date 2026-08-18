package com.example.edgegateway.service;

/** 鉴权失败（API Key 缺失/无效）：映射为 HTTP 401。 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
