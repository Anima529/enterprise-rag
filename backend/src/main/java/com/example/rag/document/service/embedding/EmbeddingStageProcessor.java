package com.example.rag.document.service.embedding;

import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.mapper.embedding.DocumentEmbeddingMapper;
import com.example.rag.document.repository.embedding.DocumentEmbeddingRepository;
import com.example.rag.document.repository.embedding.DocumentEmbeddingRepository.Snapshot;
import com.example.rag.document.service.index.IndexStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.rag.model.domain.ModelFailureCode;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.rag.model.support.EmbeddingVectors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingStageProcessor implements IndexStageProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStageProcessor.class);
    private final DocumentEmbeddingRepository repository;
    private final EmbeddingGateway gateway;
    private final EmbeddingProperties config;

    /** 配置 Chunk 快照、向量模型调用及批次限制依赖。 */
    public EmbeddingStageProcessor(
        DocumentEmbeddingRepository repository,
        EmbeddingGateway gateway,
        EmbeddingProperties config
    ) {
        this.repository = repository;
        this.gateway = gateway;
        this.config = config;
    }

    /** 声明该处理器负责 Embedding 阶段。 */
    public IndexStage stage() {
        return IndexStage.EMBEDDING;
    }

    /** 分批调用向量模型并在有效租约内提交完整版本产物。 */
    public void process(IndexTaskContext context) {
        context.checkActive();
        long start = System.nanoTime();
        long id = context.task().documentId();
        int version = context.task().documentVersion();
        Snapshot source = repository.load(id, version);
        List<List<Float>> vectors = new ArrayList<>(source.children().size());
        int batches = 0;
        int calls = 0;
        long tokens = 0;
        for (int offset = 0; offset < source.children().size(); offset += config.batchSize()) {
            context.checkActive();
            List<String> texts = source
                .children()
                .subList(offset, Math.min(offset + config.batchSize(), source.children().size()))
                .stream()
                .map(DocumentEmbeddingMapper.Child::content)
                .toList();
            EmbeddingBatch batch = gateway.embedBatch(texts);
            if (
                batch == null ||
                batch.vectors() == null ||
                batch.vectors().size() != texts.size() ||
                batch.callCount() < 1 ||
                batch.totalTokens() < 0
            ) {
                throw new IndexFailure(ModelFailureCode.MODEL_502_INVALID_BATCH, "向量化未返回完整批次", true);
            }
            for (List<Float> vector : batch.vectors()) {
                EmbeddingVectors.validate(vector, config.dimensions());
                vectors.add(List.copyOf(vector));
            }
            batches++;
            calls += batch.callCount();
            tokens += batch.totalTokens();
        }
        int totalBatches = batches;
        int totalCalls = calls;
        long totalTokens = tokens;
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        context.commit(() ->
            repository.replace(id, version, source, vectors, totalBatches, totalCalls, totalTokens, elapsed)
        );
        log.info(
            "document embedded, documentId={}, version={}, children={}, batches={}, calls={}, tokens={}, elapsedMs={}",
            id,
            version,
            vectors.size(),
            batches,
            calls,
            tokens,
            elapsed
        );
    }
}
