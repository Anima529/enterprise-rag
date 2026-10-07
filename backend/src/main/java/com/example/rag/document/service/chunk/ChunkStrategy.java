package com.example.rag.document.service.chunk;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.parser.ParsedDocument;
import java.util.List;

public interface ChunkStrategy {
    /** 按配置将解析结构切为保留来源位置的父子 Chunk。 */
    List<ChunkDraft> split(ParsedDocument document, ChunkProperties config);
}
