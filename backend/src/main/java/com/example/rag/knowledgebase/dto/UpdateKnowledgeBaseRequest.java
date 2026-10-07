package com.example.rag.knowledgebase.dto;

import com.example.rag.knowledgebase.domain.KnowledgeBaseStatus;
import jakarta.validation.constraints.*;

public record UpdateKnowledgeBaseRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 2000) String description,
    @NotNull KnowledgeBaseStatus status
) {}
