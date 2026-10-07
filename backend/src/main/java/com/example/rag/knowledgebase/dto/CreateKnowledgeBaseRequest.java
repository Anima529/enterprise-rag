package com.example.rag.knowledgebase.dto;

import jakarta.validation.constraints.*;

public record CreateKnowledgeBaseRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 2000) String description,
    @NotNull @Min(1) @Max(9007199254740991L) Long departmentId,
    @Min(1) @Max(9007199254740991L) Long ownerUserId
) {}
