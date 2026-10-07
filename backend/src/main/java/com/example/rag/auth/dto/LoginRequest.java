package com.example.rag.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{2,63}") String username,
    @NotBlank @Size(max = 72) String password
) {
    /** 输出脱敏登录参数，避免日志泄露密码。 */
    @Override
    public String toString() {
        return "LoginRequest[credentials redacted]";
    }
}
