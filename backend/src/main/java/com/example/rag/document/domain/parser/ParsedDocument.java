package com.example.rag.document.domain.parser;

import com.example.rag.document.domain.DocumentFormat;
import java.util.List;

public record ParsedDocument(DocumentFormat format, List<ParsedBlock> blocks, List<String> warnings) {
    /** 复制正文块和告警列表，保持解析产物不可变。 */
    public ParsedDocument {
        blocks = List.copyOf(blocks);
        warnings = List.copyOf(warnings);
    }
}
