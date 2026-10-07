package com.example.rag.document.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.document")
public record DocumentProperties(
    @Min(1) @Max(104857600) int maxBytes,
    @Min(1) int maxTextCharacters,
    @Min(1) int maxZipEntries,
    @Min(1) long maxZipExpandedBytes,
    @Min(1) int maxZipEntryBytes,
    @Min(1) int maxZipCompressionRatio,
    @Min(1) int maxPdfPages,
    @Min(1) @Max(300) int downloadTtlSeconds,
    @NotNull Duration storageTimeout,
    @NotNull Duration cleanupGrace,
    @NotNull Duration cleanupInterval,
    @Min(1) @Max(100) int cleanupBatchSize
) {
    /** 检查存储与清理时限为正，且补偿宽限期覆盖存储请求。 */
    @AssertTrue(
        message = "Storage timeout and cleanup durations must be positive; grace must exceed twice the storage timeout"
    )
    public boolean isValidDurations() {
        return (
            positive(storageTimeout) &&
            positive(cleanupInterval) &&
            positive(cleanupGrace) &&
            cleanupGrace.compareTo(storageTimeout.multipliedBy(2)) > 0
        );
    }

    /** 判断可选时长是否存在且为正值。 */
    private boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
