CREATE TABLE document_parse_result (
    document_id BIGINT NOT NULL,
    document_version INTEGER NOT NULL,
    source_format VARCHAR(16) NOT NULL CHECK (source_format IN ('PDF','DOCX','XLSX','TXT','MD')),
    parser_version VARCHAR(32) NOT NULL,
    block_count INTEGER NOT NULL CHECK (block_count > 0),
    character_count INTEGER NOT NULL CHECK (character_count > 0),
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(warnings) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, document_version),
    FOREIGN KEY (document_id, document_version) REFERENCES document_version(document_id, version_no) ON DELETE CASCADE
);

CREATE TABLE document_parsed_block (
    document_id BIGINT NOT NULL,
    document_version INTEGER NOT NULL,
    block_index INTEGER NOT NULL CHECK (block_index >= 0),
    text TEXT NOT NULL CHECK (length(btrim(text)) > 0),
    page INTEGER CHECK (page > 0),
    chapter TEXT,
    section TEXT,
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, document_version, block_index),
    FOREIGN KEY (document_id, document_version) REFERENCES document_parse_result(document_id, document_version) ON DELETE CASCADE
);

CREATE TRIGGER document_parse_result_updated_at BEFORE UPDATE ON document_parse_result
    FOR EACH ROW EXECUTE FUNCTION update_business_timestamp();
CREATE TRIGGER document_parsed_block_updated_at BEFORE UPDATE ON document_parsed_block
    FOR EACH ROW EXECUTE FUNCTION update_business_timestamp();
