package com.example.rag.document.config.parser;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.parser")
public record ParserProperties(
    @NotNull Duration timeout,
    @Min(1) @Max(100000) int maxBlocks,
    @Min(1) @Max(1000) int maxSheets,
    @Min(1) int maxRowsPerSheet,
    @Min(1) @Max(16384) int maxColumns,
    @Min(1) int maxCells,
    @Min(1) @Max(1000) int excelRowGroupSize,
    @Min(1) int maxSharedStrings,
    @Min(2) int pdfRepeatedMarginMinPages,
    @DecimalMin("0.5") @DecimalMax("1.0") double pdfRepeatedMarginRatio,
    @DecimalMin("0.0") double pdfColumnMinGap,
    @DecimalMin(value = "0.0", inclusive = false) double pdfColumnSpaceMultiplier,
    @DecimalMin("0.0") double pdfColumnAlignmentTolerance
) {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /** 检查解析超时为正且位于允许范围内。 */
    @AssertTrue(message = "Parser timeout must be positive and at most 10 minutes")
    public boolean isValidTimeout() {
        return timeout != null && !timeout.isZero() && !timeout.isNegative() && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }
}
