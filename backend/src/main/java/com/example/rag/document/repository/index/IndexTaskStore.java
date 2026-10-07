package com.example.rag.document.repository.index;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.index.IndexTaskProperties;
import com.example.rag.document.domain.DocumentProtocol;
import com.example.rag.document.domain.index.IndexFailureCode;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.mapper.index.IndexTaskMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IndexTaskStore {

    private static final int OLD_VERSION_CLEANUP_TIMEOUT_MULTIPLIER = 2;

    private final IndexTaskMapper mapper;
    private final IndexTaskProperties properties;
    private final TransactionTemplate transactions;

    /** 配置租约状态持久化及事务提交边界。 */
    public IndexTaskStore(IndexTaskMapper mapper, IndexTaskProperties properties, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.properties = properties;
        transactions = new TransactionTemplate(manager);
    }

    /** 在事务内领取一个有效来源任务并建立独占租约。 */
    public IndexTaskLease claim() {
        return transactions.execute(transaction -> {
            Long id = mapper.nextPending();
            if (id == null) {
                return null;
            }
            mapper.claim(id, UUID.randomUUID().toString(), properties.taskTimeout().toMillis());
            IndexTaskLease task = mapper.lock(id);
            setDocumentStage(task, IndexStage.PARSING);
            mapper.startStep(task, IndexStage.PARSING);
            return task;
        });
    }

    /** 重新校验任务租约、状态和来源是否仍有效。 */
    public void checkActive(IndexTaskLease lease) {
        requireActive(mapper.find(lease.id()), lease);
    }

    /** 在锁与租约双重校验下提交产物，超时则回滚整个事务。 */
    public void commit(IndexTaskLease lease, Runnable databaseWrites) {
        transactions.executeWithoutResult(transaction -> {
            requireActive(mapper.lock(lease.id()), lease);
            databaseWrites.run();
            // 写入事务超过租约期限时回滚，避免过期任务提交结果。
            requireActive(mapper.find(lease.id()), lease);
        });
    }

    /** 记录阶段成功并推进下一阶段，完成时切换有效文档版本。 */
    public void advance(IndexTaskLease lease, IndexStage expected) {
        transactions.executeWithoutResult(transaction -> {
            IndexTaskLease task = mapper.lock(lease.id());
            requireActive(task, lease);
            if (task.stage() != expected || !expected.isProcessing()) {
                throw new IllegalStateException("Illegal stage transition");
            }
            IndexStage next = expected.next();
            mapper.finishStep(task, DocumentProtocol.COMPLETED, null, null);
            setDocumentStage(task, next);
            if (next == IndexStage.COMPLETED) {
                mapper.complete(task.id());
            } else {
                mapper.stage(task.id(), next);
                mapper.startStep(task, next);
            }
        });
    }

    /** 按当前租约记录脱敏失败原因及有限重试计划。 */
    public void fail(IndexTaskLease lease, IndexFailure failure) {
        transactions.executeWithoutResult(transaction -> {
            IndexTaskLease task = mapper.lock(lease.id());
            if (
                task != null &&
                DocumentProtocol.RUNNING.equals(task.status()) &&
                lease.leaseOwner().equals(task.leaseOwner())
            ) {
                failLocked(task, failure);
            }
        });
    }

    /** 恢复过期或来源失效任务，阻止旧 Worker 再次提交。 */
    public List<Long> recover() {
        return transactions.execute(transaction -> {
            List<Long> ids = mapper.recoverable(properties.recoveryBatchSize());
            for (long id : ids) {
                IndexTaskLease task = mapper.lock(id);
                IndexFailure failure = task.live()
                    ? new IndexFailure(
                          IndexFailureCode.DOC_504_TASK_TIMEOUT,
                          "任务超时或执行进程中断，已回收租约",
                          true
                      )
                    : new IndexFailure(
                          IndexFailureCode.DOC_409_SOURCE_UNAVAILABLE,
                          "文档已删除、版本已切换或知识库已停用",
                          false
                      );
                failLocked(task, failure);
            }
            return ids;
        });
    }

    /** 锁定失败任务后安排手动重试，保留原任务记录。 */
    public void retry(long documentId, long taskId) {
        transactions.executeWithoutResult(transaction -> {
            IndexTaskLease task = mapper.lock(taskId);
            if (task == null || task.documentId() != documentId) {
                throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
            }
            if (!task.live() || !DocumentProtocol.FAILED.equals(task.status())) {
                throw new BusinessException(ErrorCode.DOC_409_TASK_NOT_RETRYABLE);
            }
            mapper.manualRetry(taskId);
            setDocumentStage(task, IndexStage.UPLOADED);
        });
    }

    /** 在已锁定的任务上保存失败状态、阶段记录及下次尝试时间。 */
    private void failLocked(IndexTaskLease task, IndexFailure failure) {
        boolean retry = task.live() && failure.retryable() && task.autoRetryCount() < properties.maxAutoRetries();
        mapper.finishStep(task, DocumentProtocol.FAILED, failure.code(), failure.getMessage());
        setDocumentStage(task, IndexStage.FAILED);
        mapper.fail(
            task.id(),
            retry ? DocumentProtocol.PENDING : DocumentProtocol.FAILED,
            failure.code(),
            failure.getMessage(),
            retry,
            retry ? properties.backoffMillis(task.autoRetryCount()) : 0
        );
    }

    /** 同步索引状态到文档版本和符合条件的文档主记录。 */
    private void setDocumentStage(IndexTaskLease task, IndexStage stage) {
        mapper.versionStage(task.documentId(), task.documentVersion(), stage);
        mapper.documentStage(task.documentId(), task.documentVersion(), stage);
        if (stage == IndexStage.COMPLETED && mapper.activate(task.documentId(), task.documentVersion()) > 0) {
            mapper.scheduleOld(
                task.documentId(),
                task.documentVersion(),
                properties.taskTimeout().toMillis() * OLD_VERSION_CLEANUP_TIMEOUT_MULTIPLIER
            );
        }
    }

    /** 拒绝租约不匹配、过期或来源失效的任务上下文。 */
    private void requireActive(IndexTaskLease task, IndexTaskLease lease) {
        if (
            Thread.currentThread().isInterrupted() ||
            task == null ||
            !task.live() ||
            !task.leaseValid() ||
            !DocumentProtocol.RUNNING.equals(task.status()) ||
            !lease.leaseOwner().equals(task.leaseOwner())
        ) {
            throw new LostTaskLease();
        }
    }
}
