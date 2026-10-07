package com.example.rag.evaluation.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 离线评估采用单实例单工作线程与数据库互斥领取，所有调用仍受分布式模型限额约束。 */
@Validated
@ConfigurationProperties("rag.evaluation")
public record EvaluationProperties(
    boolean enabled,
    @Min(1) @Max(1000) int maxCases,
    @Min(1) int maxPendingRuns,
    @Min(1) @Max(100) int metricK,
    @NotNull Duration pollInterval,
    @NotNull Duration caseTimeout,
    @NotNull Duration lease,
    @Min(1) int judgeOutputTokens
) {
    /** 租约必须大于单题预算，避免正常执行被恢复器错误回收。 */
    @AssertTrue(message = "评估时限必须为正且租约大于单题预算")
    public boolean isValid() {
        return (
            pollInterval != null &&
            pollInterval.isPositive() &&
            caseTimeout != null &&
            caseTimeout.isPositive() &&
            lease != null &&
            lease.compareTo(caseTimeout) > 0
        );
    }
}
