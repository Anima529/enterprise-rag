package com.example.rag.rag.adaptive.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.adaptive")
public record AdaptiveProperties(
    boolean enabled,
    @Min(1) @Max(10) int historyRounds,
    @Min(64) int historyTokens,
    @Min(1) @Max(4) int maxSubqueries,
    @Min(2) @Max(8) int maxModelCalls,
    @Min(64) int outputTokens,
    @NotNull Duration callTimeout,
    @NotNull Duration planningTimeout,
    @NotNull Duration retrievalTimeout
) {
    /** 确保模型调用与总规划预算均为正且具有合理的包含关系。 */
    @AssertTrue
    public boolean isValid() {
        return callTimeout.isPositive() && planningTimeout.compareTo(callTimeout) >= 0 && retrievalTimeout.isPositive();
    }
}
