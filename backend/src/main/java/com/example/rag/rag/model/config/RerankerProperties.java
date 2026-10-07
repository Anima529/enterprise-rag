package com.example.rag.rag.model.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.reranker")
public record RerankerProperties(
    @NotBlank String model,
    @NotBlank String endpoint,
    @NotNull String apiKey,
    @NotNull String dashscopeApiKey,
    @NotNull Duration connectTimeout,
    @NotNull Duration timeout,
    @Min(1) @Max(32) int maxConcurrency,
    @Min(1) @Max(40) int maxCandidates,
    @Min(1) @Max(5) int topN,
    @Min(1) int maxTextTokens,
    @Min(1) int maxRequestTokens
) {
    /** 校验精排连接预算不超过整个调用预算，且不允许无限时间等待。 */
    @AssertTrue(message = "Reranker budgets must be positive and coherent")
    public boolean isValid() {
        return (
            connectTimeout != null &&
            timeout != null &&
            !connectTimeout.isNegative() &&
            !connectTimeout.isZero() &&
            !timeout.isNegative() &&
            !timeout.isZero() &&
            timeout.compareTo(connectTimeout) >= 0 &&
            maxRequestTokens >= maxTextTokens
        );
    }
}
