package com.example.rag.document.dto.parser;

import com.example.rag.document.dto.preview.SourcePreviewModels.PreviewBlock;
import java.util.List;
import java.util.Map;

public record ParsedBlockResponse(
    int blockIndex,
    String text,
    Integer page,
    String chapter,
    String section,
    Map<String, Object> metadata,
    List<PreviewBlock> preview
) {}
