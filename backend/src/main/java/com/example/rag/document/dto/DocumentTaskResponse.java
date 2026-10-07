package com.example.rag.document.dto;

import java.time.Instant;

public record DocumentTaskResponse(
    long id,
    long documentId,
    int documentVersion,
    String taskType,
    String status,
    int retryCount,
    String errorCode,
    String errorMessage,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt,
    String stage,
    int attemptCount,
    int autoRetryCount,
    Instant nextAttemptAt,
    Instant leaseExpiresAt
) {}
