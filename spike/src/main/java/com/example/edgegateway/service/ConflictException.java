package com.example.edgegateway.service;

/** 业务冲突（如同站点数据源重名），映射为 HTTP 409。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
