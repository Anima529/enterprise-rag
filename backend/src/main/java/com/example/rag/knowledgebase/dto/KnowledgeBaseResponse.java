package com.example.rag.knowledgebase.dto;

import com.example.rag.knowledgebase.domain.KnowledgeBaseStatus;
import com.example.rag.knowledgebase.domain.Permission;
import java.time.Instant;

public record KnowledgeBaseResponse(
    Long id,
    String name,
    String description,
    Long departmentId,
    String departmentName,
    Long ownerUserId,
    String ownerUsername,
    String visibility,
    KnowledgeBaseStatus status,
    String embeddingModel,
    Integer embeddingDimensions,
    String generationModel,
    Instant createdAt,
    Instant updatedAt,
    Permission permission
) {}
