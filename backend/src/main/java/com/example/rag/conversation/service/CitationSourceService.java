package com.example.rag.conversation.service;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.conversation.dto.ConversationModels.*;
import com.example.rag.conversation.mapper.ConversationMapper;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.dto.DownloadResponse;
import com.example.rag.document.service.DocumentApplicationService;
import com.example.rag.document.support.parser.SourceMetadata;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CitationSourceService {

    public record Location(
        Integer page,
        String chapter,
        String section,
        String sheetName,
        Integer rowStart,
        Integer rowEnd,
        Integer lineStart,
        Integer lineEnd,
        Integer blockIndex,
        Integer charStart,
        Integer charEnd
    ) {}

    public record Source(Citation citation, DocumentFormat format, List<Location> locations, boolean fullEvidence) {}

    private final ConversationMapper mapper;
    private final PermissionScopeService permissions;
    private final DocumentApplicationService documents;
    private final CitationPreviewBuilder previews;
    private final ObjectMapper json;

    /** 配置会话归属、文档实时权限以及历史来源快照恢复依赖。 */
    public CitationSourceService(
        ConversationMapper mapper,
        PermissionScopeService permissions,
        DocumentApplicationService documents,
        CitationPreviewBuilder previews,
        ObjectMapper json
    ) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.documents = documents;
        this.previews = previews;
        this.json = json;
    }

    /** 每次打开原文重新校验会话所有权和文档 READ，失效 ACL 不能通过历史消息获得原件。 */
    public Source source(long conversationId, long messageId, String citationId) {
        long userId = permissions.currentUser().id();
        ConversationMapper.SourceRow row = mapper.source(conversationId, messageId, citationId, userId);
        if (row == null) {
            throw new BusinessException(ErrorCode.CHAT_404_NOT_FOUND);
        }
        CitationSnapshot snapshot = mapper
            .citations(messageId)
            .stream()
            .filter(citation -> citation.citationId().equals(citationId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_404_NOT_FOUND));
        documents.detail(snapshot.documentId());
        Citation original = previews.restore(snapshot);
        if (row.metadataJson() == null) {
            return new Source(original, row.format(), List.of(), false);
        }
        try {
            JsonNode sources = json.readTree(row.metadataJson()).path(SourceMetadata.SOURCES);
            Citation full = new Citation(
                original.citationId(),
                original.documentId(),
                original.documentVersion(),
                original.documentName(),
                original.page(),
                original.chapter(),
                original.section(),
                original.lineStart(),
                original.lineEnd(),
                original.chunkId(),
                row.content(),
                previews.tables(sources, row.content().length()),
                previews.preview(sources, row.content())
            );
            return new Source(full, row.format(), locations(sources, row.content().length()), true);
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid citation source metadata", failure);
        }
    }

    /** 每次打开 PDF 都重新复查引用归属和 READ，签发引用版本的限时预览地址。 */
    public DownloadResponse pdf(long conversationId, long messageId, String citationId) {
        Source source = source(conversationId, messageId, citationId);
        return documents.previewPdf(source.citation().documentId(), source.citation().documentVersion());
    }

    /** 只列出实际入模字符范围对应的页、章节、工作表和行，避免展示被预算截掉的来源。 */
    private List<Location> locations(JsonNode sources, int length) {
        List<Location> locations = new ArrayList<>();
        int offset = 0;
        for (JsonNode source : sources) {
            if (offset >= length) {
                break;
            }
            JsonNode metadata = source.path(SourceMetadata.METADATA);
            int start = source.path(SourceMetadata.CHAR_START).asInt();
            int end = Math.min(source.path(SourceMetadata.CHAR_END).asInt(), start + length - offset);
            locations.add(
                new Location(
                    integer(source, "page"),
                    text(source, SourceMetadata.CHAPTER),
                    text(source, SourceMetadata.SECTION),
                    text(metadata, SourceMetadata.SHEET_NAME),
                    integer(metadata, SourceMetadata.ROW_START),
                    integer(metadata, SourceMetadata.ROW_END),
                    integer(metadata, SourceMetadata.LINE_START),
                    integer(metadata, SourceMetadata.LINE_END),
                    integer(source, SourceMetadata.BLOCK_INDEX),
                    start,
                    end
                )
            );
            offset += source.path(SourceMetadata.CHAR_END).asInt() - start + 2;
        }
        return List.copyOf(locations);
    }

    /** 缺少来源数字时保留 null，不把未知页码或行号伪装成零。 */
    private Integer integer(JsonNode node, String field) {
        return node.path(field).isIntegralNumber() ? node.path(field).intValue() : null;
    }

    /** 缺少来源名称时保留 null，避免界面展示 JSON 的默认空字符串。 */
    private String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).textValue() : null;
    }
}
