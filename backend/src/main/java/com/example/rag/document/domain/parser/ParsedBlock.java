package com.example.rag.document.domain.parser;

import java.util.Map;

/** 正文属于不可信数据；位置指向原始文件，不使用归一化后的偏移量。 */
public record ParsedBlock(String text, Integer page, String chapter, String section, Map<String, Object> metadata) {
    /** 复制解析元数据，保持产物来源快照不可变。 */
    public ParsedBlock {
        metadata = Map.copyOf(metadata);
    }
}
