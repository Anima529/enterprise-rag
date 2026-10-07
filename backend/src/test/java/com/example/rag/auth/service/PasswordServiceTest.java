package com.example.rag.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.rag.auth.config.AuthProperties;
import com.example.rag.auth.dto.LoginRequest;
import com.example.rag.auth.dto.LoginResponse;
import org.junit.jupiter.api.Test;

class PasswordServiceTest {

    private final PasswordService service = new PasswordService(new AuthProperties(10));

    /** 验证 BCrypt 摘要带随机盐，错误及缺失凭据均不匹配。 */
    @Test
    void shouldStoreSaltedBcryptHashesAndRejectWrongOrUnknownCredentials() {
        String raw = "test-password-with-entropy";
        String hash = service.hash(raw);
        assertThat(hash).startsWith("$2a$10$").hasSize(60).isNotEqualTo(raw).isNotEqualTo(service.hash(raw));
        assertThat(service.matches(raw, hash)).isTrue();
        assertThat(service.matches("wrong-password", hash)).isFalse();
        assertThat(service.matches(raw, null)).isFalse();
    }

    /** 验证 BCrypt 密码限制按 UTF-8 字节计算。 */
    @Test
    void shouldRejectPasswordsBeyondBcryptUtf8ByteLimit() {
        assertThat(service.validLength("a".repeat(72))).isTrue();
        assertThat(service.validLength("a".repeat(73))).isFalse();
        assertThat(service.validLength("密".repeat(24))).isTrue();
        assertThat(service.validLength("密".repeat(25))).isFalse();
    }

    /** 验证登录 DTO 日志隐藏密码与 Token。 */
    @Test
    void shouldRedactCredentialsFromDtoLogging() {
        assertThat(new LoginRequest("admin", "secret-value").toString()).doesNotContain("secret-value");
        assertThat(new LoginResponse("secret-token", null).toString()).doesNotContain("secret-token");
    }
}
