package com.example.rag.document.worker.index;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.document.domain.index.IndexFailureCode;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.repository.index.IndexTaskStore;
import com.example.rag.document.service.index.IndexStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class IndexTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexTaskWorker.class);
    private final IndexTaskStore store;
    private final Map<IndexStage, IndexStageProcessor> processors = new EnumMap<>(IndexStage.class);

    /** 登记活动阶段处理器，拒绝重复或终态阶段注册。 */
    public IndexTaskWorker(IndexTaskStore store, List<IndexStageProcessor> processors) {
        this.store = store;
        for (IndexStageProcessor processor : processors) {
            if (
                !processor.stage().isProcessing() || this.processors.putIfAbsent(processor.stage(), processor) != null
            ) {
                throw new IllegalArgumentException("Duplicate or invalid index stage processor");
            }
        }
    }

    /** 按阶段顺序执行处理器，区分租约失效、可重试失败和未预期异常。 */
    public void run(IndexTaskLease task) {
        String trace = UUID.randomUUID().toString();
        try (
            MDC.MDCCloseable request = MDC.putCloseable(
                TraceContextKeys.REQUEST_ID,
                "index-" + task.id() + "-" + task.attemptCount()
            );
            MDC.MDCCloseable tracing = MDC.putCloseable(TraceContextKeys.TRACE_ID, trace);
            MDC.MDCCloseable document = MDC.putCloseable(
                TraceContextKeys.USAGE_DOCUMENT_ID,
                Long.toString(task.documentId())
            )
        ) {
            IndexTaskContext context = new IndexTaskContext(task, store);
            try {
                for (IndexStage stage = IndexStage.PARSING; stage != IndexStage.COMPLETED; stage = stage.next()) {
                    context.checkActive();
                    IndexStageProcessor processor = processors.get(stage);
                    if (processor == null) {
                        throw new IndexFailure(IndexFailureCode.DOC_503_STAGE_UNAVAILABLE, missingStage(stage), false);
                    }
                    log.info(
                        "index stage started, taskId={}, documentId={}, version={}, attempt={}, stage={}",
                        task.id(),
                        task.documentId(),
                        task.documentVersion(),
                        task.attemptCount(),
                        stage
                    );
                    processor.process(context);
                    store.advance(task, stage);
                }
                log.info(
                    "document indexed, taskId={}, documentId={}, version={}",
                    task.id(),
                    task.documentId(),
                    task.documentVersion()
                );
            } catch (LostTaskLease exception) {
                log.info("index worker fenced, taskId={}, documentId={}", task.id(), task.documentId());
            } catch (IndexFailure failure) {
                store.fail(task, failure);
                log.warn(
                    "index task failed, taskId={}, documentId={}, code={}, retryable={}",
                    task.id(),
                    task.documentId(),
                    failure.code(),
                    failure.retryable()
                );
            } catch (RuntimeException exception) {
                store.fail(
                    task,
                    new IndexFailure(IndexFailureCode.DOC_500_INDEX_FAILED, "索引处理异常，请查看任务日志后重试", false)
                );
                log.error(
                    "index task failed, taskId={}, documentId={}, exceptionType={}",
                    task.id(),
                    task.documentId(),
                    exception.getClass().getSimpleName()
                );
            }
        }
    }

    /** 为未注册的阶段处理器生成明确失败，避免伪装成索引成功。 */
    private String missingStage(IndexStage stage) {
        return switch (stage) {
            case PARSING -> "解析处理器尚未接入（Phase 6），原文件仍可下载";
            case CHUNKING -> "分块处理器尚未接入（Phase 7）";
            case EMBEDDING, INDEXING -> "向量化或索引处理器尚未接入（Phase 8）";
            default -> throw new IllegalArgumentException("Not a processing stage");
        };
    }
}
