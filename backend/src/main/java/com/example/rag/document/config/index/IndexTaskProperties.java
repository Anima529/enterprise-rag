package com.example.rag.document.config.index;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.index-task")
public record IndexTaskProperties(
    boolean enabled,
    @Min(1) @Max(16) int workers,
    @NotNull Duration pollInterval,
    @NotNull Duration taskTimeout,
    @Min(0) @Max(10) int maxAutoRetries,
    @NotNull Duration retryBackoff,
    @NotNull Duration maxRetryBackoff,
    @Min(1) @Max(100) int recoveryBatchSize
) {
    private static final int MAX_BACKOFF_EXPONENT = 10;

    /** 校验轮询、租约和重试时限之间的配置约束。 */
    @AssertTrue(message = "Task durations must be positive and maximum backoff must be at least initial backoff")
    public boolean isValidDurations() {
        return (
            positive(pollInterval) &&
            positive(taskTimeout) &&
            positive(retryBackoff) &&
            positive(maxRetryBackoff) &&
            maxRetryBackoff.compareTo(retryBackoff) >= 0
        );
    }

    /** 检查时长是否存在且为正。 */
    private boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    /** 计算受上限约束的指数退避时长。 */
    public long backoffMillis(int retry) {
        return Math.min(
            maxRetryBackoff.toMillis(),
            Math.multiplyExact(retryBackoff.toMillis(), 1L << Math.min(retry, MAX_BACKOFF_EXPONENT))
        );
    }
}
