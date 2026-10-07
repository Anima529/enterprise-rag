package com.example.rag.document.repository.embedding;

import com.example.rag.document.domain.DocumentProtocol;
import com.example.rag.document.domain.index.IndexFailureCode;
import com.example.rag.document.dto.embedding.EmbeddingPreviewResponse;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.mapper.chunk.DocumentChunkMapper;
import com.example.rag.document.mapper.chunk.DocumentChunkMapper.Summary;
import com.example.rag.document.mapper.embedding.DocumentEmbeddingMapper;
import com.example.rag.document.mapper.embedding.DocumentEmbeddingMapper.*;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.rag.model.support.EmbeddingVectors;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class DocumentEmbeddingRepository {

    public record Snapshot(Summary summary, List<Child> children) {}

    private final DocumentEmbeddingMapper mapper;
    private final DocumentChunkMapper chunks;
    private final EmbeddingProperties config;
    private final TransactionTemplate reads;

    /** 配置向量一致性查询，建立可重复读的只读事务。 */
    public DocumentEmbeddingRepository(
        DocumentEmbeddingMapper mapper,
        DocumentChunkMapper chunks,
        EmbeddingProperties config,
        PlatformTransactionManager manager
    ) {
        this.mapper = mapper;
        this.chunks = chunks;
        this.config = config;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** 读取本次生成向量所需的 Chunk 快照和内容。 */
    public Snapshot load(long id, int version) {
        return reads.execute(transaction -> {
            Summary summary = chunks.summary(id, version);
            List<Child> children = mapper.children(id, version);
            if (summary == null || children.size() != summary.childCount() || children.isEmpty()) {
                throw inconsistent();
            }
            return new Snapshot(summary, List.copyOf(children));
        });
    }

    /** 仅在任务租约保护的短提交事务内调用。 */
    public void replace(
        long id,
        int version,
        Snapshot source,
        List<List<Float>> vectors,
        int batches,
        int calls,
        long tokens,
        long durationMs
    ) {
        Summary current = chunks.summary(id, version);
        if (
            !source.summary().equals(current) ||
            !source.children().equals(mapper.children(id, version)) ||
            vectors.size() != source.children().size()
        ) {
            throw inconsistent();
        }
        List<VectorRow> rows = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            Child child = source.children().get(i);
            rows.add(
                new VectorRow(
                    child.id(),
                    EmbeddingVectors.literal(vectors.get(i), config.dimensions()),
                    EmbeddingVectors.hash(child.content())
                )
            );
        }
        for (int start = 0; start < rows.size(); start += config.persistenceBatchSize()) {
            List<VectorRow> batch = rows.subList(start, Math.min(start + config.persistenceBatchSize(), rows.size()));
            if (mapper.write(id, version, batch, config.model(), config.dimensions()) != batch.size()) {
                throw inconsistent();
            }
        }
        mapper.save(
            new Result(
                id,
                version,
                config.model(),
                config.dimensions(),
                source.summary().chunkedAt(),
                rows.size(),
                batches,
                calls,
                tokens,
                durationMs,
                null
            )
        );
        verify(id, version);
    }

    /** 校验全部子块向量与 Chunk 快照一致后允许索引完成。 */
    public void verify(long id, int version) {
        Result indexResult = mapper.result(id, version);
        Summary summary = chunks.summary(id, version);
        if (
            !consistent(id, version, indexResult, summary) ||
            !config.model().equals(indexResult.model()) ||
            config.dimensions() != indexResult.dimensions()
        ) {
            throw inconsistent();
        }
    }

    /** 核对模型、维度、切分时间和有效向量数量。 */
    private boolean consistent(long id, int version, Result indexResult, Summary summary) {
        return (
            indexResult != null &&
            summary != null &&
            indexResult.childCount() == summary.childCount() &&
            mapper.children(id, version).size() == indexResult.childCount() &&
            mapper.validChildren(id, version, indexResult.model(), indexResult.dimensions()) == indexResult.childCount()
        );
    }

    /** 返回版本的授权 Embedding 汇总，隐藏原始向量。 */
    public EmbeddingPreviewResponse preview(long id, int version) {
        return reads.execute(transaction -> {
            Result indexResult = mapper.result(id, version);
            if (!consistent(id, version, indexResult, chunks.summary(id, version))) {
                return new EmbeddingPreviewResponse(id, version, false, false, null, null, 0, 0, 0, 0, 0, null);
            }
            return new EmbeddingPreviewResponse(
                id,
                version,
                true,
                DocumentProtocol.COMPLETED.equals(mapper.status(id, version)),
                indexResult.model(),
                indexResult.dimensions(),
                indexResult.childCount(),
                indexResult.batchCount(),
                indexResult.callCount(),
                indexResult.totalTokens(),
                indexResult.durationMs(),
                indexResult.embeddedAt()
            );
        });
    }

    /** 构造向量产物不完整或快照失效的阶段错误。 */
    private IndexFailure inconsistent() {
        return new IndexFailure(
            IndexFailureCode.DOC_409_EMBEDDING_INCONSISTENT,
            "分块或向量不完整、正文已变化，请重新索引",
            false
        );
    }
}
