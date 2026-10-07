package com.example.rag.document.repository.chunk;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.chunk.ChunkDraft.SourceSpan;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.dto.chunk.ChunkPreviewResponse;
import com.example.rag.document.dto.chunk.ChunkResponse;
import com.example.rag.document.mapper.chunk.DocumentChunkMapper;
import com.example.rag.document.mapper.chunk.DocumentChunkMapper.*;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.document.service.chunk.StructureAwareParentChildChunkStrategy;
import com.example.rag.document.service.preview.SourcePreviewBuilder;
import com.example.rag.document.support.parser.SourceMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class DocumentChunkRepository {

    private final DocumentChunkMapper mapper;
    private final ObjectMapper json;
    private final ChunkProperties config;
    private final TransactionTemplate reads;
    private final SourcePreviewBuilder previews;

    /** 配置 Chunk 持久化和来源预览，建立可重复读的只读事务。 */
    public DocumentChunkRepository(
        DocumentChunkMapper mapper,
        ObjectMapper json,
        ChunkProperties config,
        PlatformTransactionManager manager,
        SourcePreviewBuilder previews
    ) {
        this.mapper = mapper;
        this.json = json;
        this.config = config;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.previews = previews;
    }

    /** 仅在 IndexTaskContext.commit 内调用，以阻止过期工作线程写入并支持所有批次一起回滚。 */
    public void replace(long id, int version, List<ChunkDraft> drafts) {
        Scope scope = scope(id, version);
        Map<String, Long> ids = new HashMap<>();
        for (Key key : mapper.keys(id, version)) {
            ids.put(key(key.chunkType(), key.chunkIndex()), key.id());
        }
        int missing = (int) drafts
            .stream()
            .filter(draft -> !ids.containsKey(key(draft.chunkType(), draft.chunkIndex())))
            .count();
        List<Long> allocated = missing == 0 ? List.of() : mapper.allocateIds(missing);
        int next = 0;
        for (ChunkDraft draft : drafts) {
            String key = key(draft.chunkType(), draft.chunkIndex());
            if (!ids.containsKey(key)) {
                ids.put(key, allocated.get(next++));
            }
        }
        List<Row> rows = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            SourceSpan source = draft.sources().getFirst();
            Long parentChunkId =
                draft.parentIndex() == null ? null : ids.get(key(ChunkType.PARENT, draft.parentIndex()));
            rows.add(
                new Row(
                    ids.get(key(draft.chunkType(), draft.chunkIndex())),
                    id,
                    version,
                    scope.knowledgeBaseId(),
                    scope.departmentId(),
                    parentChunkId,
                    draft.chunkType(),
                    draft.chunkIndex(),
                    draft.content(),
                    draft.tokenCount(),
                    source.page(),
                    source.chapter(),
                    source.section(),
                    metadata(draft)
                )
            );
        }
        int parents = (int) drafts
            .stream()
            .filter(draft -> draft.chunkType() == ChunkType.PARENT)
            .count();
        mapper.upsertSummary(
            id,
            version,
            StructureAwareParentChildChunkStrategy.VERSION,
            ChunkTokenizer.NAME,
            config,
            parents,
            drafts.size() - parents
        );
        mapper.invalidateEmbeddings(id, version);
        mapper.hide(id, version);
        for (int start = 0; start < rows.size(); start += config.batchSize()) {
            mapper.upsertRows(rows.subList(start, Math.min(start + config.batchSize(), rows.size())));
        }
    }

    /** 在只读事务中读取指定版本的 Chunk 预览。 */
    public ChunkPreviewResponse preview(long id, int version, ChunkType type, PageRequest page) {
        return reads.execute(transaction -> readPreview(id, version, type, page));
    }

    /** 基于同一快照组合 Chunk 汇总、分页内容与来源预览。 */
    private ChunkPreviewResponse readPreview(long id, int version, ChunkType type, PageRequest page) {
        scope(id, version);
        Summary summary = mapper.summary(id, version);
        if (summary == null) {
            return new ChunkPreviewResponse(
                id,
                version,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                List.of(),
                0,
                page.page(),
                page.pageSize()
            );
        }
        List<ChunkResponse> records = mapper
            .rows(id, version, type, page.pageSize(), page.offset())
            .stream()
            .map(this::response)
            .toList();
        long total;
        if (type == null) {
            total = summary.parentCount() + summary.childCount();
        } else if (type == ChunkType.PARENT) {
            total = summary.parentCount();
        } else {
            total = summary.childCount();
        }
        return new ChunkPreviewResponse(
            id,
            version,
            true,
            summary.strategyVersion(),
            summary.tokenizer(),
            summary.chunkedAt(),
            summary.childTokens(),
            summary.parentTokens(),
            summary.overlapTokens(),
            summary.boundaryLookbackCharacters(),
            summary.parentCount(),
            summary.childCount(),
            records,
            total,
            page.page(),
            page.pageSize()
        );
    }

    /** 要求文档版本来源有效并返回知识库与部门配对。 */
    private Scope scope(long id, int version) {
        Scope scope = mapper.scope(id, version);
        if (scope == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        return scope;
    }

    /** 用 Chunk 类型和序号建立稳定 ID 的匹配键。 */
    private String key(ChunkType type, int index) {
        return type.name() + ":" + index;
    }

    /** 序列化切分片段的结构与来源元数据。 */
    private String metadata(ChunkDraft draft) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SourceMetadata.SOURCES, draft.sources());
        try {
            return json.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize chunk metadata", exception);
        }
    }

    /** 将存储行转换为含来源预览的 Chunk 响应。 */
    private ChunkResponse response(Row row) {
        try {
            Map<String, Object> metadata = json.readValue(
                row.metadataJson(),
                new TypeReference<Map<String, Object>>() {}
            );
            return new ChunkResponse(
                row.id(),
                row.documentId(),
                row.documentVersion(),
                row.knowledgeBaseId(),
                row.departmentId(),
                row.parentChunkId(),
                row.chunkType(),
                row.chunkIndex(),
                row.content(),
                row.tokenCount(),
                row.pageNumber(),
                row.chapter(),
                row.section(),
                metadata,
                previews.preview(json.valueToTree(metadata).path(SourceMetadata.SOURCES), row.content())
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted chunk metadata", exception);
        }
    }
}
