package com.example.rag.rag.chat.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.chat")
public record ChatProperties(
    @NotBlank String model,
    @NotBlank String baseUrl,
    @NotNull String apiKey,
    @NotNull String dashscopeApiKey,
    @Min(1) @Max(100) int topK,
    @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity,
    @Min(256) int contextTokens,
    @Min(1) int minContextTokens,
    @Min(1) int questionTokens,
    @Min(1) int maxOutputTokens,
    @Min(1) int maxOutputCharacters,
    @Min(1) int snippetCharacters,
    @Min(1) @Max(32) int maxConcurrency,
    @NotNull Duration connectTimeout,
    @NotNull Duration generationTimeout,
    @NotNull Duration timeout,
    @NotNull Duration heartbeat
) {
    /** 检查聊天资源、时限与 Token 预算之间的配置约束。 */
    @AssertTrue(message = "Chat timeouts and budgets must be positive and coherent")
    public boolean isValid() {
        return (
            positive(connectTimeout) &&
            positive(generationTimeout) &&
            positive(timeout) &&
            positive(heartbeat) &&
            timeout.compareTo(generationTimeout) > 0 &&
            timeout.compareTo(heartbeat) > 0 &&
            contextTokens > minContextTokens
        );
    }

    /** 检查聊天时长配置是否为正值。 */
    private boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }
}
