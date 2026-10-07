package com.example.rag.document.config.chunk;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.chunk")
public record ChunkProperties(
    @Min(16) @Max(8192) int childTokens,
    @Min(16) @Max(32768) int parentTokens,
    @Min(0) int overlapTokens,
    @Min(2) @Max(100000) int maxChunks,
    @NotNull Duration timeout,
    @Min(1) @Max(1000) int batchSize,
    @Min(0) @Max(1024) int boundaryLookbackCharacters
) {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /** 检查父子 Token 上限及重叠范围之间的约束。 */
    @AssertTrue(message = "Parent size must be at least child size and overlap must be smaller than child size")
    public boolean isValidSizes() {
        return parentTokens >= childTokens && overlapTokens < childTokens;
    }

    /** 限制切分超时为正且不超过十分钟。 */
    @AssertTrue(message = "Chunk timeout must be positive and at most 10 minutes")
    public boolean isValidTimeout() {
        return timeout != null && !timeout.isNegative() && !timeout.isZero() && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }
}
