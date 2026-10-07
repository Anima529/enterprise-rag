package com.example.rag.document.service;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentProtocol;
import com.example.rag.document.mapper.DocumentVersionMapper;
import com.example.rag.document.storage.upload.DocumentStorage;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DocumentCleanup {

    private final DocumentVersionMapper mapper;
    private final DocumentStorage storage;
    private final DocumentProperties config;
    private final TransactionTemplate tx;
    private static final Logger log = LoggerFactory.getLogger(DocumentCleanup.class);

    /** 配置版本清理与对象删除依赖，建立清理事务边界。 */
    public DocumentCleanup(
        DocumentVersionMapper mapper,
        DocumentStorage storage,
        DocumentProperties config,
        PlatformTransactionManager manager
    ) {
        this.mapper = mapper;
        this.storage = storage;
        this.config = config;
        tx = new TransactionTemplate(manager);
    }

    /** 领取到期清理任务，删除失效索引或未被有效版本引用的文件并记录结果。 */
    @Scheduled(
        fixedDelayString = "${rag.document.cleanup-interval}",
        initialDelayString = "${rag.document.cleanup-interval}"
    )
    public void sweep() {
        try (
            MDC.MDCCloseable request = MDC.putCloseable(TraceContextKeys.REQUEST_ID, UUID.randomUUID().toString());
            MDC.MDCCloseable trace = MDC.putCloseable(TraceContextKeys.TRACE_ID, UUID.randomUUID().toString())
        ) {
            for (int i = 0; i < config.cleanupBatchSize(); i++) {
                DocumentVersionMapper.Cleanup task = mapper.claim(config.storageTimeout().toMillis() * 2);
                if (task == null) {
                    return;
                }
                try {
                    if (DocumentProtocol.DELETED.equals(task.cleanupType())) {
                        for (String key : mapper.keys(task.documentId())) {
                            if (!mapper.activeReference(key)) {
                                storage.remove(key);
                            }
                        }
                    }
                    tx.executeWithoutResult(transaction -> {
                        mapper.removeChunks(task.documentId(), task.documentVersion());
                        mapper.removeParsed(task.documentId(), task.documentVersion());
                        mapper.finish(task, DocumentProtocol.COMPLETED, null, Instant.now());
                    });
                } catch (RuntimeException failure) {
                    mapper.finish(
                        task,
                        DocumentProtocol.FAILED,
                        ErrorCode.STORAGE_503_UNAVAILABLE.name(),
                        Instant.now().plus(config.cleanupInterval())
                    );
                    log.warn("document cleanup deferred, taskId={}, documentId={}", task.id(), task.documentId());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("document cleanup unavailable, exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
