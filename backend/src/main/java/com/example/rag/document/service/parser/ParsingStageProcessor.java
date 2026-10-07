package com.example.rag.document.service.parser;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.mapper.parser.ParsedDocumentMapper;
import com.example.rag.document.repository.parser.ParsedDocumentRepository;
import com.example.rag.document.service.index.IndexStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import com.example.rag.document.storage.upload.DocumentStorage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class ParsingStageProcessor implements IndexStageProcessor {

    private final DocumentStorage storage;
    private final DocumentProperties properties;
    private final DocumentParsingService parser;
    private final ParsedDocumentRepository repository;

    /** 配置私有原文件读取、解析及产物持久化依赖。 */
    public ParsingStageProcessor(
        DocumentStorage storage,
        DocumentProperties properties,
        DocumentParsingService parser,
        ParsedDocumentRepository repository
    ) {
        this.storage = storage;
        this.properties = properties;
        this.parser = parser;
        this.repository = repository;
    }

    /** 声明该处理器负责原文件解析阶段。 */
    public IndexStage stage() {
        return IndexStage.PARSING;
    }

    /** 读取私有原文件、核验摘要并在有效租约内提交解析产物。 */
    public void process(IndexTaskContext context) {
        context.checkActive();
        long id = context.task().documentId();
        int version = context.task().documentVersion();
        ParsedDocumentMapper.Source source = repository.source(id, version);
        byte[] content;
        try {
            content = storage.read(source.objectKey(), properties.maxBytes());
        } catch (BusinessException exception) {
            boolean retryable = exception.errorCode() == ErrorCode.STORAGE_503_UNAVAILABLE;
            throw new IndexFailure(
                retryable ? ErrorCode.STORAGE_503_UNAVAILABLE.name() : ErrorCode.DOC_413_PARSE_LIMIT.name(),
                retryable ? "原文件存储暂时不可用" : "原文件超过解析资源上限",
                retryable
            );
        }
        context.checkActive();
        if (content.length != source.sizeBytes() || !sha256(content).equals(source.sha256())) {
            throw ParseBudget.invalid();
        }
        ParsedDocument parsedDocument = parser.parse(source.format(), new ByteArrayInputStream(content));
        context.commit(() -> repository.replace(id, version, parsedDocument));
    }

    /** 计算原文件 SHA-256，用于核对存储内容与版本摘要。 */
    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 required by JVM");
        }
    }
}
