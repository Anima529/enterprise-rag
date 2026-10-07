package com.example.rag.document.dto.chunk;

import java.time.Instant;
import java.util.List;

public record ChunkPreviewResponse(
    long documentId,
    int documentVersion,
    boolean available,
    String strategyVersion,
    String tokenizer,
    Instant chunkedAt,
    Integer childTokens,
    Integer parentTokens,
    Integer overlapTokens,
    Integer boundaryLookbackCharacters,
    int parentCount,
    int childCount,
    List<ChunkResponse> records,
    long total,
    int page,
    int pageSize
) {}
