package com.example.edgegateway.web.dto;

/** 统一业务码信封：{code, message, data}；code 稳定不变，message 可随文案调整。 */
public record ApiResponse<T>(
        String code,
        String message,
        T data
) {

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>("OK", "ok", null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "ok", data);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
