package com.example.rag.rag.faithfulness.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.faithfulness")
public record FaithfulnessProperties(
    boolean enabled,
    @NotNull Duration timeout,
    @Min(512) int inputTokens,
    @Min(64) int outputTokens,
    @DecimalMin("0.0") @DecimalMax("1.0") double mediumCoverage,
    @Min(1) int maxUnsupportedClaims,
    @Min(1) int maxClaimCharacters
) {
    /** 禁止配置无效时限，避免 Judge 不受资源预算约束。 */
    @AssertTrue
    public boolean isValid() {
        return timeout.isPositive();
    }
}
