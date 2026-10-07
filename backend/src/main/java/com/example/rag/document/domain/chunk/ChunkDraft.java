package com.example.rag.document.domain.chunk;

import java.util.List;
import java.util.Map;

public record ChunkDraft(
    ChunkType chunkType,
    int chunkIndex,
    Integer parentIndex,
    String content,
    int tokenCount,
    List<SourceSpan> sources
) {
    /** 复制来源列表，防止切分产物被外部修改。 */
    public ChunkDraft {
        sources = List.copyOf(sources);
    }

    /** 持久化 ParsedBlock 正文中的 UTF-16 偏移量，结束位置不包含在范围内。 */
    public record SourceSpan(
        int blockIndex,
        int charStart,
        int charEnd,
        Integer page,
        String chapter,
        String section,
        Map<String, Object> metadata
    ) {
        /** 复制来源元数据，保持切分位置快照不可变。 */
        public SourceSpan {
            metadata = Map.copyOf(metadata);
        }
    }
}
