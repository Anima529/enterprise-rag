-- Preserve the actual model of existing vectors. Only re-indexing generates new-model vectors.
ALTER TABLE document_chunk DROP CONSTRAINT ck_chunk_embedding;
ALTER TABLE document_chunk ADD CONSTRAINT ck_chunk_embedding CHECK (
    (embedding IS NULL AND embedding_model IS NULL AND embedding_dimensions IS NULL AND embedding_content_hash IS NULL)
    OR (chunk_type = 'CHILD' AND embedding IS NOT NULL
        AND embedding_model IS NOT NULL AND length(btrim(embedding_model)) > 0
        AND embedding_dimensions IS NOT NULL AND embedding_dimensions = 1024
        AND embedding_content_hash IS NOT NULL AND embedding_content_hash ~ '^[0-9a-f]{64}$'
        AND vector_norm(embedding) > 0)
);
ALTER TABLE document_embedding_result DROP CONSTRAINT document_embedding_result_model_check;
ALTER TABLE document_embedding_result ADD CONSTRAINT document_embedding_result_model_check
    CHECK (length(btrim(model)) > 0);

COMMENT ON COLUMN knowledge_base.embedding_model IS '当前应用配置的向量模型；启动时同步，向量实际来源见 document_chunk.embedding_model';
COMMENT ON COLUMN knowledge_base.embedding_dimensions IS '当前应用配置的向量维度；须匹配 vector(1024) 存储结构';
COMMENT ON COLUMN knowledge_base.generation_model IS '当前应用配置的回答生成模型；启动时同步';
COMMENT ON COLUMN document_chunk.embedding_model IS '实际生成该向量的模型；检索必须与当前查询向量模型一致，切换模型需重建索引';
COMMENT ON COLUMN document_embedding_result.model IS '实际生成该版本向量的模型；切换配置不改写历史向量来源';
