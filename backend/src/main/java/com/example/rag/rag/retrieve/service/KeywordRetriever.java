package com.example.rag.rag.retrieve.service;

import com.example.rag.common.security.RetrievalScope;
import com.example.rag.rag.retrieve.mapper.KeywordSearchMapper;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository;
import com.example.rag.rag.retrieve.support.KeywordAnalyzer;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KeywordRetriever {

    private static final int MAX_SQL_TOP_K = 100;

    public record KeywordHit(
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
        String section,
        double keywordScore
    ) {
        /** 保留原始 FTS 分数，将来源转换为上下文使用的公共证据结构。 */
        public VectorSearchRepository.Hit hit() {
            return new VectorSearchRepository.Hit(
                chunkId,
                parentChunkId,
                knowledgeBaseId,
                departmentId,
                documentId,
                documentVersion,
                content,
                metadataJson,
                distance,
                parentContent,
                documentName,
                pageNumber,
                chapter,
                section
            );
        }
    }

    private final KeywordSearchMapper mapper;

    /** 配置只接受授权范围的 FTS 查询入口。 */
    public KeywordRetriever(KeywordSearchMapper mapper) {
        this.mapper = mapper;
    }

    /** 使用同一分析器转换自然语言问题，空词项直接返回空候选。 */
    @Transactional(readOnly = true, timeoutString = "${rag.retrieval.keyword-sql-timeout-seconds}")
    public List<KeywordHit> search(RetrievalScope scope, String question, int topK) {
        if (scope == null || topK < 1 || topK > MAX_SQL_TOP_K) {
            throw new IllegalArgumentException("Authorized scope and bounded topK required");
        }
        String query = KeywordAnalyzer.query(question);
        if (query.isEmpty()) {
            return List.of();
        }
        return mapper.search(scope, query, topK, KeywordAnalyzer.VERSION);
    }
}
