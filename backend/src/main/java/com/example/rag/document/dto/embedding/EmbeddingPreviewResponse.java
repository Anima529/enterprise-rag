package com.example.rag.document.dto.embedding;

import java.time.Instant;

public record EmbeddingPreviewResponse(
    long documentId,
    int documentVersion,
    boolean available,
    boolean indexed,
    String model,
    Integer dimensions,
    int childCount,
    int batchCount,
    int callCount,
    long totalTokens,
    long durationMs,
    Instant embeddedAt
) {}
