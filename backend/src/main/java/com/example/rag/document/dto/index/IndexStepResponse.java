package com.example.rag.document.dto.index;

import java.time.Instant;

public record IndexStepResponse(
    long id,
    long taskId,
    int attempt,
    String stage,
    String status,
    Instant startedAt,
    Instant finishedAt,
    String errorCode,
    String errorMessage
) {}
