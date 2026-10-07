package com.example.rag.common.api;

import java.util.Objects;

public record ApiError(String code, String message, String requestId) {
    /** 检查错误码及追踪 ID 满足统一错误协议。 */
    public ApiError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(requestId, "requestId");
        if (!code.matches("[A-Z]+_[45][0-9]{2}_[A-Z_]+") || requestId.isBlank()) {
            throw new IllegalArgumentException("Error code or requestId violates the V1 contract");
        }
    }
}
