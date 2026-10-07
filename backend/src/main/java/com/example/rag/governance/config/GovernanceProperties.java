package com.example.rag.governance.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 分布式限流、并发租约和语义缓存的资源预算。 */
@Validated
@ConfigurationProperties("rag.governance")
public record GovernanceProperties(
    boolean enabled,
    @Min(1) int userRequests,
    @Min(1) int departmentRequests,
    @NotNull Duration window,
    @Min(1) int modelConcurrency,
    @NotNull Duration modelLease,
    boolean cacheEnabled,
    @NotNull Duration cacheTtl,
    @Min(1) @Max(100) int cacheEntries,
    @DecimalMin("0.0") @DecimalMax("1.0") double cacheSimilarity,
    @Min(1) int cacheMaxCharacters
) {
    /** 防止失效租约无限保留或缓存资源无界增长。 */
    @AssertTrue(message = "治理时间预算必须为正")
    public boolean isValid() {
        return positive(window) && positive(modelLease) && positive(cacheTtl);
    }

    /** 校验各时间配置均显式设定正值。 */
    private boolean positive(Duration duration) {
        return duration != null && duration.isPositive();
    }
}
