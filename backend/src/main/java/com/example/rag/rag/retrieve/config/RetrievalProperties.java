package com.example.rag.rag.retrieve.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.retrieval")
public record RetrievalProperties(
    @NotNull Complexity defaultComplexity,
    @Min(1) @Max(256) int workers,
    @Min(1) @Max(1000) int queueCapacity,
    @Min(1) int rrfK,
    @NotNull Duration vectorTimeout,
    @NotNull Duration keywordTimeout,
    @Min(1) @Max(30) int vectorSqlTimeoutSeconds,
    @Min(1) @Max(30) int keywordSqlTimeoutSeconds,
    @DecimalMin("0.0") @DecimalMax("1.0") double relativeScoreFloor,
    @Min(1) @Max(10) int maxParentsPerDocument,
    @Min(1) @Max(1000) int backfillBatchSize,
    @NotNull @Valid Profile simple,
    @NotNull @Valid Profile standard,
    @NotNull @Valid Profile complex
) {
    public enum Complexity {
        SIMPLE,
        STANDARD,
        COMPLEX,
    }

    public record Profile(
        @Min(1) @Max(100) int vectorTopK,
        @Min(1) @Max(100) int keywordTopK,
        @Min(1) @Max(40) int fusionTopK,
        @Min(1) @Max(5) int finalTopK
    ) {}

    /** 检查独立召回预算为正，避免提交无限等待的任务。 */
    @AssertTrue(message = "Retrieval timeouts must be positive")
    public boolean isValid() {
        return (
            vectorTimeout != null &&
            !vectorTimeout.isNegative() &&
            !vectorTimeout.isZero() &&
            keywordTimeout != null &&
            !keywordTimeout.isNegative() &&
            !keywordTimeout.isZero()
        );
    }

    /** 返回指定复杂度的候选与最终证据上限；自动路由由 Phase 16 接入。 */
    public Profile profile(Complexity complexity) {
        return switch (complexity) {
            case SIMPLE -> simple;
            case STANDARD -> standard;
            case COMPLEX -> complex;
        };
    }
}
