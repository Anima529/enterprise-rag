package com.example.rag.document.service.index;

import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.repository.index.IndexTaskStore;

/** 所有产物写入必须通过 commit，并按文档、版本和稳定分块键执行替换或 upsert。 */
public record IndexTaskContext(IndexTaskLease task, IndexTaskStore store) {
    /** 确认租约仍有效且线程未中断。 */
    public void checkActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new LostTaskLease();
        }
        store.checkActive(task);
    }

    /** 在租约校验保护的事务中提交阶段数据库写入。 */
    public void commit(Runnable databaseWrites) {
        store.commit(task, databaseWrites);
    }
}
