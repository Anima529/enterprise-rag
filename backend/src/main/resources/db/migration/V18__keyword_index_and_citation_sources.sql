-- 词项与 Chunk 共用版本、软删除和清理边界；历史词项由 Java 分析器在启动时分批补齐。
ALTER TABLE document_chunk
    ADD COLUMN keyword_text text NOT NULL DEFAULT '',
    ADD COLUMN keyword_analyzer varchar(40) NOT NULL DEFAULT '',
    ADD COLUMN keyword_content_hash varchar(64),
    ADD COLUMN keyword_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', keyword_text)) STORED;

CREATE INDEX idx_chunk_keyword_gin ON document_chunk USING gin(keyword_vector)
    WHERE chunk_type = 'CHILD' AND NOT deleted;

COMMENT ON COLUMN document_chunk.keyword_text IS 'han-bigram-v1 归一化去重词项，空格分隔';
COMMENT ON COLUMN document_chunk.keyword_analyzer IS '词项分析器版本，查询仅使用当前版本';
COMMENT ON COLUMN document_chunk.keyword_content_hash IS '词项对应正文的 SHA-256，拒绝陈旧词项';
COMMENT ON COLUMN document_chunk.keyword_vector IS '由词项生成的 PostgreSQL simple FTS 向量，排名不等于 BM25';

-- 保存实际入模正文和来源范围，避免索引清理或版本切换后定位到其他内容。
ALTER TABLE message_citation
    ADD COLUMN evidence_content text,
    ADD COLUMN source_metadata jsonb;

COMMENT ON COLUMN message_citation.evidence_content IS '本次入模的完整证据正文，受上下文 Token 预算限制';
COMMENT ON COLUMN message_citation.source_metadata IS '入模 Parent 的有序来源元数据，预览按实际正文长度裁剪';
