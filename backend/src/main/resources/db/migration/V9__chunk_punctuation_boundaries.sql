-- NULL identifies historical results generated before configurable punctuation alignment.
ALTER TABLE document_chunk_result ADD COLUMN boundary_lookback_characters INTEGER
    CHECK (boundary_lookback_characters BETWEEN 0 AND 1024);
