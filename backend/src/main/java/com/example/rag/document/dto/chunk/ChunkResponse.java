package com.example.rag.document.dto.chunk;

import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.dto.preview.SourcePreviewModels.PreviewBlock;
import java.util.List;
import java.util.Map;

public record ChunkResponse(
    long id,
    long documentId,
    int documentVersion,
    long knowledgeBaseId,
    long departmentId,
    Long parentChunkId,
    ChunkType chunkType,
    int chunkIndex,
    String content,
    int tokenCount,
    Integer pageNumber,
    String chapter,
    String section,
    Map<String, Object> metadata,
    List<PreviewBlock> preview
) {}
