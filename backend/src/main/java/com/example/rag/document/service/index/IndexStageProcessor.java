package com.example.rag.document.service.index;

import com.example.rag.document.domain.index.IndexStage;

/** Phase 6–8 的实现将 IO 放在事务外，使用 context.commit() 持久化结果。 */
public interface IndexStageProcessor {
    /** 声明处理器负责的唯一索引阶段。 */
    IndexStage stage();

    /** 在任务上下文约束下执行阶段并提交对应产物。 */
    void process(IndexTaskContext context);
}
