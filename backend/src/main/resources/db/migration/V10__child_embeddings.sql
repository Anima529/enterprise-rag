ALTER TABLE document_chunk ADD COLUMN embedding vector(1024);
ALTER TABLE document_chunk ADD COLUMN embedding_model VARCHAR(64);
ALTER TABLE document_chunk ADD COLUMN embedding_dimensions INTEGER;
ALTER TABLE document_chunk ADD COLUMN embedding_content_hash CHAR(64);
ALTER TABLE document_chunk ADD CONSTRAINT ck_chunk_embedding CHECK (
    (embedding IS NULL AND embedding_model IS NULL AND embedding_dimensions IS NULL AND embedding_content_hash IS NULL)
    OR (chunk_type = 'CHILD' AND embedding IS NOT NULL AND embedding_model = 'text-embedding-v3'
        AND embedding_dimensions = 1024 AND embedding_content_hash IS NOT NULL
        AND embedding_content_hash ~ '^[0-9a-f]{64}$' AND vector_norm(embedding) > 0)
);
CREATE INDEX idx_chunk_vector_scope ON document_chunk
    (knowledge_base_id, department_id, document_id, document_version, id)
    WHERE NOT deleted AND chunk_type = 'CHILD' AND embedding IS NOT NULL;

-- Exact cosine ranking is intentional for the current filtered corpus. See Phase 8 EXPLAIN experiment.
CREATE TABLE document_embedding_result (
    document_id BIGINT NOT NULL,
    document_version INTEGER NOT NULL,
    model VARCHAR(64) NOT NULL CHECK (model = 'text-embedding-v3'),
    dimensions INTEGER NOT NULL CHECK (dimensions = 1024),
    chunked_at TIMESTAMPTZ NOT NULL,
    child_count INTEGER NOT NULL CHECK (child_count > 0),
    batch_count INTEGER NOT NULL CHECK (batch_count > 0),
    call_count INTEGER NOT NULL CHECK (call_count >= batch_count),
    total_tokens BIGINT NOT NULL CHECK (total_tokens >= 0),
    duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, document_version),
    FOREIGN KEY (document_id, document_version) REFERENCES document_chunk_result(document_id, document_version) ON DELETE CASCADE
);
CREATE TRIGGER document_embedding_result_updated BEFORE UPDATE ON document_embedding_result
    FOR EACH ROW EXECUTE FUNCTION update_business_timestamp();
