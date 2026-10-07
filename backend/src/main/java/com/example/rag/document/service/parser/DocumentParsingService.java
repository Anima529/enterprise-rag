package com.example.rag.document.service.parser;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.config.parser.ParserProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DocumentParsingService {

    private final Map<DocumentFormat, DocumentParser> parsers = new EnumMap<>(DocumentFormat.class);
    private final ParserProperties properties;
    private final DocumentProperties documents;

    /** 按格式登记唯一解析器，要求支持全部允许的文档格式。 */
    public DocumentParsingService(
        List<DocumentParser> parsers,
        ParserProperties properties,
        DocumentProperties documents
    ) {
        for (DocumentParser parser : parsers) {
            if (this.parsers.putIfAbsent(parser.format(), parser) != null) {
                throw new IllegalArgumentException("Duplicate parser");
            }
        }
        if (this.parsers.size() != DocumentFormat.values().length) {
            throw new IllegalArgumentException("All document formats require a parser");
        }
        this.properties = properties;
        this.documents = documents;
    }

    /** 选择格式解析器并归一化结构，拒绝空输出及资源越界。 */
    public ParsedDocument parse(DocumentFormat format, InputStream input) {
        ParseBudget budget = new ParseBudget(properties, documents);
        try {
            ParsedDocument parsedDocument = parsers.get(format).parse(input, budget);
            budget.check();
            if (parsedDocument.blocks().isEmpty()) {
                throw new IndexFailure(ErrorCode.DOC_422_EMPTY_TEXT.name(), "文档未提取到可用正文", false);
            }
            ParsedDocument normalized = new TableNormalizer().normalize(parsedDocument, budget.outputBudget());
            if (normalized.blocks().isEmpty()) {
                throw new IndexFailure(ErrorCode.DOC_422_EMPTY_TEXT.name(), "文档未提取到可用正文", false);
            }
            return normalized;
        } catch (IndexFailure | LostTaskLease exception) {
            throw exception;
        } catch (BusinessException exception) {
            if (exception.errorCode() == ErrorCode.DOC_413_TOO_LARGE) {
                throw new IndexFailure(ErrorCode.DOC_413_PARSE_LIMIT.name(), "Office 文档内容超过资源上限", false);
            }
            throw ParseBudget.invalid();
        } catch (Exception exception) {
            throw ParseBudget.invalid();
        }
    }
}
