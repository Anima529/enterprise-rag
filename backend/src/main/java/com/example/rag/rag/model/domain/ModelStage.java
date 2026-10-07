package com.example.rag.rag.model.domain;

/** 模型调用账本与 MDC 使用的稳定阶段名称，保持现有存储和统计协议。 */
public enum ModelStage {
    REWRITE,
    ROUTE,
    DECOMPOSE,
    HYDE,
    FAITHFULNESS,
    GENERATION,
    EMBEDDING,
    RERANK,
}
