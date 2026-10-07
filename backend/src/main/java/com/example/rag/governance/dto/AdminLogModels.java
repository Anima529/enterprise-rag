package com.example.rag.governance.dto;

import com.example.rag.common.api.ApiContractLimits;
import com.example.rag.governance.domain.RequestLogStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** 日志查询和公开元数据契约，不包含凭据、正文或检索证据。 */
public final class AdminLogModels {

    /** 日志模型通过嵌套 record 使用，无需创建容器实例。 */
    private AdminLogModels() {
    }

    public record AuditQuery(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @Min(1) @Max(ApiContractLimits.MAX_SAFE_ID) Long userId,
        @Size(max = 120) String requestId,
        @Size(max = 80) String action
    ) {}

    public record RequestQuery(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @Min(1) @Max(ApiContractLimits.MAX_SAFE_ID) Long userId,
        @Size(max = 120) String requestId,
        RequestLogStatus status
    ) {}

    public record LogFilter(
        Instant from,
        Instant to,
        Long userId,
        String requestId,
        String action,
        RequestLogStatus status
    ) {}

    public record AuditLog(
        long id,
        Long userId,
        String username,
        String action,
        Long resourceId,
        String requestId,
        String traceId,
        Instant createdAt
    ) {}

    public record RequestLog(
        String requestId,
        String traceId,
        long userId,
        String username,
        String departmentName,
        Long messageId,
        String route,
        String reasonCode,
        RequestLogStatus status,
        long latencyMs,
        Long firstTokenMs,
        boolean cacheHit,
        String errorCode,
        Instant createdAt,
        Instant updatedAt
    ) {}
}
