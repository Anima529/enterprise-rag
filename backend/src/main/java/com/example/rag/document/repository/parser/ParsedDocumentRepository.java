package com.example.rag.document.repository.parser;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.parser.ParsedDocumentPersistenceProperties;
import com.example.rag.document.domain.index.IndexFailureCode;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.dto.parser.ParsePreviewResponse;
import com.example.rag.document.dto.parser.ParsedBlockResponse;
import com.example.rag.document.mapper.parser.ParsedDocumentMapper;
import com.example.rag.document.mapper.parser.ParsedDocumentMapper.BlockRow;
import com.example.rag.document.mapper.parser.ParsedDocumentMapper.ResultWrite;
import com.example.rag.document.mapper.parser.ParsedDocumentMapper.Source;
import com.example.rag.document.mapper.parser.ParsedDocumentMapper.Summary;
import com.example.rag.document.service.preview.SourcePreviewBuilder;
import com.example.rag.document.support.parser.SourceMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ParsedDocumentRepository {

    private static final String PARSER_VERSION = "phase6-v5-source-structure";
    private final ParsedDocumentMapper mapper;
    private final ObjectMapper json;
    private final ParsedDocumentPersistenceProperties properties;
    private final TransactionTemplate reads;
    private final SourcePreviewBuilder previews;

    /** 配置解析 JSON 与来源预览，建立可重复读的只读事务。 */
    public ParsedDocumentRepository(
        ParsedDocumentMapper mapper,
        ObjectMapper json,
        ParsedDocumentPersistenceProperties properties,
        PlatformTransactionManager manager,
        SourcePreviewBuilder previews
    ) {
        this.mapper = mapper;
        this.json = json;
        this.properties = properties;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.previews = previews;
    }

    /** 要求文档版本有效并读取原文件对象信息。 */
    public Source source(long id, int version) {
        Source source = mapper.source(id, version);
        if (source == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        return source;
    }

    /** 在只读快照中加载完整版本解析产物。 */
    public ParsedDocument load(long id, int version) {
        return reads.execute(transaction -> loadParsedDocument(id, version));
    }

    /** 组合解析汇总和所有块，供后续 Chunk 阶段使用。 */
    private ParsedDocument loadParsedDocument(long id, int version) {
        Source source = source(id, version);
        Summary summary = mapper.summary(id, version);
        if (summary == null) {
            throw new com.example.rag.document.exception.index.IndexFailure(
                IndexFailureCode.DOC_409_PARSE_REQUIRED,
                "文档尚无解析结果，无法分块",
                false
            );
        }
        List<ParsedBlock> blocks = mapper
            .blocks(id, version, Math.toIntExact(summary.blockCount()), 0)
            .stream()
            .map(row ->
                new ParsedBlock(
                    row.text(),
                    row.page(),
                    row.chapter(),
                    row.section(),
                    read(row.metadataJson(), new TypeReference<Map<String, Object>>() {})
                )
            )
            .toList();
        return new ParsedDocument(
            source.format(),
            blocks,
            read(summary.warningsJson(), new TypeReference<List<String>>() {})
        );
    }

    /** 调用方必须使用 IndexTaskContext.commit；同一版本的替换操作保持原子性和确定性。 */
    public void replace(long id, int version, ParsedDocument document) {
        int characters = document
            .blocks()
            .stream()
            .mapToInt(block -> block.text().length())
            .sum();
        ResultWrite parseResult = new ResultWrite(
            id,
            version,
            document.format(),
            PARSER_VERSION,
            document.blocks().size(),
            characters,
            write(document.warnings())
        );
        List<BlockRow> rows = new ArrayList<>(document.blocks().size());
        for (int i = 0; i < document.blocks().size(); i++) {
            ParsedBlock block = document.blocks().get(i);
            rows.add(
                new BlockRow(i, block.text(), block.page(), block.chapter(), block.section(), write(block.metadata()))
            );
        }
        mapper.upsertResult(parseResult);
        mapper.deleteBlocks(id, version);
        for (int start = 0; start < rows.size(); start += properties.batchSize()) {
            int end = Math.min(start + properties.batchSize(), rows.size());
            mapper.insertBlocks(id, version, rows.subList(start, end));
        }
    }

    /** 在只读事务中查询授权入口所需的解析预览。 */
    public ParsePreviewResponse preview(long id, int version, PageRequest page) {
        return reads.execute(transaction -> readPreview(id, version, page));
    }

    /** 基于同一快照组合版本汇总和分页解析块。 */
    private ParsePreviewResponse readPreview(long id, int version, PageRequest page) {
        Source source = source(id, version);
        Summary summary = mapper.summary(id, version);
        if (summary == null) {
            return new ParsePreviewResponse(
                id,
                version,
                source.format(),
                false,
                null,
                null,
                0,
                List.of(),
                List.of(),
                0,
                page.page(),
                page.pageSize()
            );
        }
        List<BlockRow> rows = mapper.blocks(id, version, page.pageSize(), page.offset());
        List<ParsedBlockResponse> blocks = rows.stream().map(this::block).toList();
        List<String> warnings = read(summary.warningsJson(), new TypeReference<List<String>>() {});
        return new ParsePreviewResponse(
            id,
            version,
            source.format(),
            true,
            summary.parserVersion(),
            summary.parsedAt(),
            summary.characterCount(),
            warnings,
            blocks,
            summary.blockCount(),
            page.page(),
            page.pageSize()
        );
    }

    /** 将存储行还原为带来源元数据的解析块。 */
    private ParsedBlockResponse block(BlockRow row) {
        Map<String, Object> metadata = read(row.metadataJson(), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> position = new java.util.LinkedHashMap<>(metadata);
        position.put("tableRowComplete", true);
        return new ParsedBlockResponse(
            row.blockIndex(),
            row.text(),
            row.page(),
            row.chapter(),
            row.section(),
            metadata,
            previews.preview(
                json.valueToTree(
                    List.of(
                        Map.of(
                            SourceMetadata.CHAR_START,
                            0,
                            SourceMetadata.CHAR_END,
                            row.text().length(),
                            SourceMetadata.METADATA,
                            position
                        )
                    )
                ),
                row.text()
            )
        );
    }

    /** 将解析元数据序列化为 JSON，失败时转换为阶段错误。 */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize parsed document");
        }
    }

    /** 从 JSON 还原解析元数据，避免传播底层异常。 */
    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid persisted parser metadata");
        }
    }
}
