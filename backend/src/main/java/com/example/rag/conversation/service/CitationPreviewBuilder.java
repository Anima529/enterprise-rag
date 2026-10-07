package com.example.rag.conversation.service;

import static com.example.rag.conversation.dto.ConversationModels.*;
import static com.example.rag.document.dto.preview.SourcePreviewModels.*;

import com.example.rag.document.service.preview.SourcePreviewBuilder;
import com.example.rag.document.support.parser.SourceMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CitationPreviewBuilder {

    private final ObjectMapper json;
    private final SourcePreviewBuilder sources;

    /** 配置引用 JSON 恢复与统一来源预览依赖。 */
    public CitationPreviewBuilder(ObjectMapper json, SourcePreviewBuilder sources) {
        this.json = json;
        this.sources = sources;
    }

    /** 按引用可见字符范围构造表格，避免展示片段之外的内容。 */
    public List<PreviewTable> tables(JsonNode metadata, int characters) {
        return sources.tables(metadata, characters);
    }

    /** 按证据原始顺序构造文本与表格混合预览。 */
    public List<PreviewBlock> preview(JsonNode metadata, String snippet) {
        return sources.preview(metadata, snippet);
    }

    /** 优先恢复持久化引用快照，兼容仍可关联索引的历史引用。 */
    public Citation restore(CitationSnapshot snapshot) {
        try {
            List<PreviewTable> tables = json.readValue(
                snapshot.tablesJson(),
                new TypeReference<List<PreviewTable>>() {}
            );
            List<PreviewBlock> preview = json.readValue(
                snapshot.previewJson(),
                new TypeReference<List<PreviewBlock>>() {}
            );
            if (preview.isEmpty() && tables.isEmpty() && snapshot.fallbackMetadataJson() != null) {
                JsonNode metadata = json.readTree(snapshot.fallbackMetadataJson()).path(SourceMetadata.SOURCES);
                preview = sources.preview(metadata, snapshot.snippet());
                tables = sources.tables(metadata, snapshot.snippet().length());
            }
            return new Citation(
                snapshot.citationId(),
                snapshot.documentId(),
                snapshot.documentVersion(),
                snapshot.documentName(),
                snapshot.page(),
                snapshot.chapter(),
                snapshot.section(),
                snapshot.lineStart(),
                snapshot.lineEnd(),
                snapshot.chunkId(),
                snapshot.snippet(),
                tables,
                preview
            );
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid stored citation preview", failure);
        }
    }
}
