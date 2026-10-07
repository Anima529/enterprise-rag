package com.example.rag.document.service.chunk;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.repository.chunk.DocumentChunkRepository;
import com.example.rag.document.repository.parser.ParsedDocumentRepository;
import com.example.rag.document.service.index.IndexStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChunkingStageProcessor implements IndexStageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChunkingStageProcessor.class);
    private final ParsedDocumentRepository parsed;
    private final ChunkStrategy strategy;
    private final ChunkProperties config;
    private final DocumentChunkRepository repository;

    /** 配置解析读取、切分策略和 Chunk 产物提交依赖。 */
    public ChunkingStageProcessor(
        ParsedDocumentRepository parsed,
        ChunkStrategy strategy,
        ChunkProperties config,
        DocumentChunkRepository repository
    ) {
        this.parsed = parsed;
        this.strategy = strategy;
        this.config = config;
        this.repository = repository;
    }

    /** 声明该处理器负责 Chunk 切分阶段。 */
    public IndexStage stage() {
        return IndexStage.CHUNKING;
    }

    /** 加载解析结果、切分父子 Chunk，并在有效租约内提交产物。 */
    public void process(IndexTaskContext context) {
        context.checkActive();
        long id = context.task().documentId();
        int version = context.task().documentVersion();
        ParsedDocument document = parsed.load(id, version);
        List<ChunkDraft> chunks = strategy.split(document, config);
        context.commit(() -> repository.replace(id, version, chunks));
        log.info("document chunked, documentId={}, version={}, chunks={}", id, version, chunks.size());
    }
}
