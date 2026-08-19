package com.example.edgegateway.web;

import com.example.edgegateway.service.BusinessCode;
import com.example.edgegateway.service.BusinessException;
import com.example.edgegateway.web.dto.ApiResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/** 全局异常映射：所有异常统一返回业务码信封（DS-1），消灭漏网与 Spring 默认格式。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return envelope(ex.businessCode(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalid(IllegalArgumentException ex) {
        return envelope(BusinessCode.PARAM_INVALID, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoSuchElementException ex) {
        return envelope(BusinessCode.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleState(IllegalStateException ex) {
        return envelope(BusinessCode.INTERNAL_ERROR, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return envelope(BusinessCode.PARAM_INVALID, "请求体不是合法 JSON: " + ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        return envelope(BusinessCode.INTERNAL_ERROR, ex.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> envelope(BusinessCode code, String message) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.error(code.code(), message));
    }
}
