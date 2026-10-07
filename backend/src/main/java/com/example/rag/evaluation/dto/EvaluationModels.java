package com.example.rag.evaluation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 数据集版本通过新建表达，已有案例和实验保持不可变以便对比。 */
public final class EvaluationModels {

    /** 领域模型容器不能实例化。 */
    private EvaluationModels() {
    }

    public enum Mode {
        VECTOR,
        HYBRID,
        HYBRID_RERANK,
    }

    public record CaseInput(
        @NotBlank @Size(max = 4000) String question,
        @NotEmpty @Size(max = 20) List<@Positive Long> knowledgeBaseIds,
        @NotNull @Size(max = 100) List<@Positive Long> expectedChunkIds,
        @NotNull @Size(max = 16000) String referenceAnswer,
        @NotNull @Size(max = 8000) String history,
        boolean expectDenied,
        @NotBlank @Size(max = 80) String category
    ) {}

    public record DatasetRequest(
        @NotBlank @Size(max = 160) String name,
        @NotNull @Size(max = 4000) String description,
        @Positive long evaluationUserId,
        @NotEmpty List<@Valid CaseInput> cases
    ) {}

    public record RunRequest(@NotBlank @Size(max = 160) String name, @NotNull Mode mode) {}

    public record Metrics(Double hit, Double recall, Double reciprocalRank, int relevantCount, int k) {}
}
