package com.example.rag.document.dto;

import com.example.rag.document.domain.DocumentFormat;
import java.time.Instant;

public record DocumentResponse(
    long id,
    long knowledgeBaseId,
    int currentVersion,
    String name,
    DocumentFormat sourceFormat,
    String mimeType,
    long sizeBytes,
    String sha256,
    String status,
    long createdBy,
    String createdByUsername,
    Instant createdAt,
    Instant updatedAt,
    String errorCode,
    String errorMessage
) {}
