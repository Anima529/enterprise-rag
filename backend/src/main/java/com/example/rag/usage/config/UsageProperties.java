package com.example.rag.usage.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 统计日期时区、实时快照保留期和查询跨度统一配置。 */
@Validated
@ConfigurationProperties("rag.usage")
public record UsageProperties(@NotNull ZoneId timezone, @NotNull Duration realtimeTtl, @Min(1) int maxQueryDays) {
    /** 要求缓存保留期为正，保证 Redis EX 使用有效秒数。 */
    @AssertTrue
    public boolean isValid() {
        return realtimeTtl != null && realtimeTtl.toSeconds() > 0;
    }
}
