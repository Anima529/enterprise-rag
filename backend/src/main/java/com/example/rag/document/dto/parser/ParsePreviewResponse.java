package com.example.rag.document.dto.parser;

import com.example.rag.document.domain.DocumentFormat;
import java.time.Instant;
import java.util.List;

public record ParsePreviewResponse(
    long documentId,
    int documentVersion,
    DocumentFormat format,
    boolean available,
    String parserVersion,
    Instant parsedAt,
    int characterCount,
    List<String> warnings,
    List<ParsedBlockResponse> records,
    long total,
    int page,
    int pageSize
) {}
