package com.example.rag.common.api;

public record ApiResponse<T>(int code, String message, T data) {
    /** 按照统一成功响应协议包装业务返回值。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }
}
