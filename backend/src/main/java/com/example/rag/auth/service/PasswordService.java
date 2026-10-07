package com.example.rag.auth.service;

import com.example.rag.auth.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(AuthProperties.class)
public class PasswordService {

    private static final int MAX_PASSWORD_UTF8_BYTES = 72;

    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;

    /** 配置 BCrypt 成本并预生成占位摘要，统一未知账号的密码校验路径。 */
    public PasswordService(AuthProperties properties) {
        encoder = new BCryptPasswordEncoder(properties.bcryptStrength());
        dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** 按 UTF-8 字节数检查 BCrypt 密码长度上限。 */
    public boolean validLength(String raw) {
        return raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_UTF8_BYTES;
    }

    /** 按配置的成本生成带随机盐的 BCrypt 密码摘要。 */
    public String hash(String raw) {
        return encoder.encode(raw);
    }

    /** 校验密码摘要，对缺失凭据返回不匹配。 */
    public boolean matches(String raw, String hash) {
        return encoder.matches(raw, hash == null ? dummyHash : hash) && hash != null;
    }
}
