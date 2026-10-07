package com.example.rag.usage.dto;

import com.example.rag.usage.domain.UsageProtocol;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

public final class UsageModels {

    /** 限制账本模型容器实例化。 */
    private UsageModels() {
    }

    public record PriceRequest(
        @NotBlank @Size(max = 120) String model,
        @NotBlank @Size(max = 80) String version,
        @NotBlank @Pattern(regexp = UsageProtocol.PRICE_BASIS_PATTERN) String basis,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 8) BigDecimal inputPrice,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 8) BigDecimal outputPrice
    ) {}

    public record Price(
        long id,
        String model,
        String version,
        String basis,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        String currency,
        Instant effectiveAt
    ) {}

    public record Identity(Long userId, Long departmentId, Long conversationId, Long messageId) {}

    public record CallRow(
        String requestId,
        String traceId,
        String callId,
        Identity identity,
        String stage,
        String model,
        Long inputTokens,
        Long outputTokens,
        Long units,
        long latencyMs,
        boolean success,
        String errorCode,
        Price price,
        BigDecimal cost
    ) {}
}
