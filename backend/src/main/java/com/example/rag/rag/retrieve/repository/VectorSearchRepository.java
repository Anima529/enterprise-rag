package com.example.rag.rag.retrieve.repository;

import com.example.rag.common.security.RetrievalScope;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.rag.model.support.EmbeddingVectors;
import com.example.rag.rag.retrieve.mapper.VectorSearchMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 候选查询必须使用已授权的范围，并在 SQL 内重新校验当前 ACL。 */
@Repository
public class VectorSearchRepository {

    public record Hit(
        long chunkId,
        long parentChunkId,
        long knowledgeBaseId,
        long departmentId,
        long documentId,
        int documentVersion,
        String content,
        String metadataJson,
        double distance,
        String parentContent,
        String documentName,
        Integer pageNumber,
        String chapter,
        String section
    ) {}

    private final VectorSearchMapper mapper;
    private final EmbeddingProperties config;

    /** 配置授权向量检索及模型空间约束。 */
    public VectorSearchRepository(VectorSearchMapper mapper, EmbeddingProperties config) {
        this.mapper = mapper;
        this.config = config;
    }

    /** 校验查询向量和授权范围后读取受限的相似证据。 */
    @Transactional(readOnly = true, timeoutString = "${rag.retrieval.vector-sql-timeout-seconds}")
    public List<Hit> search(RetrievalScope scope, List<Float> vector, int topK) {
        if (scope == null || topK < 1 || topK > config.maxTopK()) {
            throw new IllegalArgumentException("Authorized scope and bounded topK required");
        }
        return mapper.search(
            scope,
            EmbeddingVectors.literal(vector, config.dimensions()),
            topK,
            config.model(),
            config.dimensions()
        );
    }
}
