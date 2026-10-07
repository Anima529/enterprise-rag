ALTER TABLE message_citation ADD COLUMN preview jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(preview)='array');
COMMENT ON COLUMN message_citation.preview IS '按引用范围及来源顺序保存正文、表格和局部片段快照；历史引用保留原表格或文本';
