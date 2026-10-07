package com.example.rag.auth.dto;

import com.example.rag.user.dto.UserResponse;

public record LoginResponse(String token, UserResponse user) {
    /** 输出脱敏登录响应，避免日志泄露 Token。 */
    @Override
    public String toString() {
        return "LoginResponse[token redacted]";
    }
}
