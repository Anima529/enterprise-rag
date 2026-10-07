ALTER TABLE message_citation ADD COLUMN tables jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(tables)='array');
COMMENT ON COLUMN message_citation.tables IS '引用预览范围内的表头与完整行快照；不依赖后续索引保留，旧消息默认为空数组';
