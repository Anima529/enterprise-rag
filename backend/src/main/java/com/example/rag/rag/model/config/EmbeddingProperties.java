package com.example.rag.rag.model.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.embedding")
public record EmbeddingProperties(
    @NotBlank String model,
    int dimensions,
    @NotBlank String baseUrl,
    @NotNull String apiKey,
    @NotNull String dashscopeApiKey,
    @Min(1) @Max(10) int batchSize,
    @Min(1) @Max(2) int maxConcurrency,
    @NotNull Duration connectTimeout,
    @NotNull Duration timeout,
    @Min(0) @Max(2) int maxRetries,
    @NotNull Duration retryBackoff,
    @Min(1) @Max(1000) int persistenceBatchSize,
    @Min(1) @Max(100) int maxTopK
) {
    private static final int STORAGE_VECTOR_DIMENSIONS = 1024;
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(10);

    /** 检查配置维度是否与现有 PGVector 存储约束一致。 */
    @AssertTrue(message = "Embedding dimensions must match the vector(1024) storage schema")
    public boolean isStorageDimensionSupported() {
        return dimensions == STORAGE_VECTOR_DIMENSIONS;
    }

    /** 检查模型请求、并发等待和重试时限配置。 */
    @AssertTrue(message = "Embedding timeouts must be positive and bounded")
    public boolean isValidTimeouts() {
        return (
            connectTimeout != null &&
            timeout != null &&
            retryBackoff != null &&
            connectTimeout.isPositive() &&
            timeout.isPositive() &&
            !retryBackoff.isNegative() &&
            connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) <= 0 &&
            timeout.compareTo(Duration.ofMinutes(1)) <= 0 &&
            retryBackoff.compareTo(MAX_RETRY_BACKOFF) <= 0
        );
    }

    /** 输出脱敏模型配置，避免 API Key 进入日志。 */
    @Override
    public String toString() {
        return "EmbeddingProperties[model=" + model + ", dimensions=" + dimensions + "]";
    }
}
