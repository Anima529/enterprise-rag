package com.example.rag.document.service.upload;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.mapper.DocumentMapper;
import com.example.rag.document.storage.upload.DocumentStorage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class OrphanObjectCleanup {

    private static final Logger log = LoggerFactory.getLogger(OrphanObjectCleanup.class);
    private final DocumentMapper mapper;
    private final DocumentStorage storage;
    private final DocumentProperties properties;

    /** 配置上传补偿记录查询、对象删除和清理时限。 */
    public OrphanObjectCleanup(DocumentMapper mapper, DocumentStorage storage, DocumentProperties properties) {
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
    }

    /** 领取到期上传补偿对象，仅删除未被版本引用的原文件。 */
    @Scheduled(
        fixedDelayString = "${rag.document.cleanup-interval}",
        initialDelayString = "${rag.document.cleanup-interval}"
    )
    public void sweep() {
        try (
            MDC.MDCCloseable request = MDC.putCloseable(TraceContextKeys.REQUEST_ID, UUID.randomUUID().toString());
            MDC.MDCCloseable trace = MDC.putCloseable(TraceContextKeys.TRACE_ID, UUID.randomUUID().toString())
        ) {
            try {
                List<String> keys = mapper.claimCleanup(
                    properties.cleanupBatchSize(),
                    Instant.now().plus(properties.cleanupGrace())
                );
                for (String key : keys) {
                    try {
                        if (!mapper.objectReferenced(key)) {
                            storage.remove(key);
                        }
                        mapper.clearUpload(key);
                    } catch (RuntimeException exception) {
                        log.warn("orphan cleanup deferred, exceptionType={}", exception.getClass().getSimpleName());
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("orphan cleanup unavailable, exceptionType={}", exception.getClass().getSimpleName());
            }
        }
    }
}
