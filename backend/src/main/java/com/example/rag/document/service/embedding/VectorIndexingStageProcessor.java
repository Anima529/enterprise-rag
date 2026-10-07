package com.example.rag.document.service.embedding;

import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.repository.embedding.DocumentEmbeddingRepository;
import com.example.rag.document.service.index.IndexStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import org.springframework.stereotype.Component;

@Component
public class VectorIndexingStageProcessor implements IndexStageProcessor {

    private final DocumentEmbeddingRepository repository;

    /** 配置完成向量索引前的一致性校验依赖。 */
    public VectorIndexingStageProcessor(DocumentEmbeddingRepository repository) {
        this.repository = repository;
    }

    /** 声明该处理器负责向量索引校验阶段。 */
    public IndexStage stage() {
        return IndexStage.INDEXING;
    }

    /** 在完成索引前核验版本向量完整性。 */
    public void process(IndexTaskContext context) {
        context.commit(() -> repository.verify(context.task().documentId(), context.task().documentVersion()));
    }
}
