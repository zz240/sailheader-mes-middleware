package com.example.edgegateway.web;

import com.example.edgegateway.web.dto.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 成功响应自动包业务码信封：{code:"OK", message:"ok", data}。
 * mock 端点（/mock/**）与无 body（204）不包；错误由 ApiExceptionHandler 返回信封。
 */
@ControllerAdvice
public class ApiEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> type = returnType.getParameterType();
        return type != String.class
                && !ApiResponse.class.isAssignableFrom(type)
                && type != Void.TYPE;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null || body instanceof ApiResponse || request.getURI().getPath().startsWith("/mock/")) {
            return body;
        }
        return ApiResponse.ok(body);
    }
}
