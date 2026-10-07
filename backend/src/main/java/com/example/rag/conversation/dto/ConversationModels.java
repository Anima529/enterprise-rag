package com.example.rag.conversation.dto;

import com.example.rag.document.dto.preview.SourcePreviewModels.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ConversationModels {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private ConversationModels() {
    }

    public record TitleRequest(@NotBlank @Size(max = 100) String title) {}

    public record Conversation(long id, String title, Instant createdAt, Instant updatedAt) {}

    public record Citation(
        String citationId,
        long documentId,
        int documentVersion,
        String documentName,
        Integer page,
        String chapter,
        String section,
        Integer lineStart,
        Integer lineEnd,
        long chunkId,
        String snippet,
        List<PreviewTable> tables,
        List<PreviewBlock> preview
    ) {}

    public record CitationSnapshot(
        String citationId,
        long documentId,
        int documentVersion,
        String documentName,
        Integer page,
        String chapter,
        String section,
        Integer lineStart,
        Integer lineEnd,
        long chunkId,
        String snippet,
        String tablesJson,
        String previewJson,
        String fallbackMetadataJson
    ) {}

    public record Message(
        long id,
        long conversationId,
        String role,
        String content,
        String requestId,
        String traceId,
        String status,
        String errorCode,
        String confidenceLevel,
        String faithfulnessStatus,
        Long inputTokens,
        Long outputTokens,
        Instant createdAt,
        List<Citation> citations,
        com.example.rag.rag.faithfulness.service.EvidenceJudge.Verification verificationDetails,
        java.math.BigDecimal estimatedCost
    ) {}

    public record MessageRow(
        long id,
        long conversationId,
        String role,
        String content,
        String requestId,
        String traceId,
        String status,
        String errorCode,
        String confidenceLevel,
        String faithfulnessStatus,
        Long inputTokens,
        Long outputTokens,
        Instant createdAt,
        String verificationJson,
        java.math.BigDecimal estimatedCost
    ) {}
}
