package com.example.rag.document.service.parser;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedDocument;
import java.io.InputStream;

public interface DocumentParser {
    /** 声明解析器支持的文档格式。 */
    DocumentFormat format();

    /** 在共享解析预算内提取正文、结构和来源位置。 */
    ParsedDocument parse(InputStream input, ParseBudget budget) throws Exception;
}
