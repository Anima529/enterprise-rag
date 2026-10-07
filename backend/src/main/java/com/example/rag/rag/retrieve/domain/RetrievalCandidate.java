package com.example.rag.rag.retrieve.domain;

import com.example.rag.rag.retrieve.repository.VectorSearchRepository;

/** 分别保留两路原始分数、排名与后续精排分数，避免把 FTS 分数当成向量相似度。 */
public record RetrievalCandidate(
    VectorSearchRepository.Hit hit,
    Integer vectorRank,
    Double vectorScore,
    Integer keywordRank,
    Double keywordScore,
    double rrfScore,
    Double rerankScore
) {
    /** 在精排后保留原始检索来源，只添加 Cross-Encoder 分数。 */
    public RetrievalCandidate reranked(double score) {
        return new RetrievalCandidate(hit, vectorRank, vectorScore, keywordRank, keywordScore, rrfScore, score);
    }
}
