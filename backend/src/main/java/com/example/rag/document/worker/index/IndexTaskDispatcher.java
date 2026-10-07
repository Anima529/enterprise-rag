package com.example.rag.document.worker.index;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.document.config.index.IndexTaskProperties;
import com.example.rag.document.domain.index.IndexFailureCode;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.repository.index.IndexTaskStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class IndexTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IndexTaskDispatcher.class);
    private final IndexTaskStore store;
    private final IndexTaskWorker worker;
    private final IndexTaskProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final Map<Long, Thread> active = new ConcurrentHashMap<>();

    /** 配置任务领取、Worker 和有界执行线程池。 */
    public IndexTaskDispatcher(
        IndexTaskStore store,
        IndexTaskWorker worker,
        IndexTaskProperties properties,
        @Qualifier("documentIndexExecutor") ThreadPoolTaskExecutor executor
    ) {
        this.store = store;
        this.worker = worker;
        this.properties = properties;
        this.executor = executor;
    }

    /** 按线程池剩余容量领取到期任务，并提交给阶段 Worker。 */
    @Scheduled(
        fixedDelayString = "${rag.index-task.poll-interval}",
        initialDelayString = "${rag.index-task.poll-interval}"
    )
    public void tick() {
        if (!properties.enabled()) {
            return;
        }
        try (
            MDC.MDCCloseable request = MDC.putCloseable(TraceContextKeys.REQUEST_ID, "index-dispatch");
            MDC.MDCCloseable trace = MDC.putCloseable(TraceContextKeys.TRACE_ID, UUID.randomUUID().toString())
        ) {
            try {
                List<Long> recovered = store.recover();
                for (long id : recovered) {
                    Thread thread = active.get(id);
                    if (thread != null) {
                        thread.interrupt();
                    }
                    log.warn("index task recovered, taskId={}", id);
                }
                for (int slot = executor.getActiveCount(); slot < properties.workers(); slot++) {
                    IndexTaskLease task = store.claim();
                    if (task == null) {
                        break;
                    }
                    try {
                        executor.execute(() -> {
                            active.put(task.id(), Thread.currentThread());
                            try {
                                worker.run(task);
                            } finally {
                                active.remove(task.id(), Thread.currentThread());
                            }
                        });
                    } catch (TaskRejectedException exception) {
                        store.fail(
                            task,
                            new IndexFailure(
                                IndexFailureCode.DOC_503_EXECUTOR_BUSY,
                                "索引工作线程暂忙，将有限重试",
                                true
                            )
                        );
                        break;
                    }
                }
            } catch (RuntimeException exception) {
                log.error("index dispatch unavailable, exceptionType={}", exception.getClass().getSimpleName());
            }
        }
    }
}
