package com.example.edgegateway.web.dto;

/** 任务启停请求体：status 0 停用，1 启用。 */
public record JobStatusRequest(Integer status) {
}
