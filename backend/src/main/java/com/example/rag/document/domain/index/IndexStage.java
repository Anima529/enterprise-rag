package com.example.rag.document.domain.index;

public enum IndexStage {
    UPLOADED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETED,
    FAILED;

    /** 返回有序索引流程中的下一阶段。 */
    public IndexStage next() {
        return switch (this) {
            case UPLOADED -> PARSING;
            case PARSING -> CHUNKING;
            case CHUNKING -> EMBEDDING;
            case EMBEDDING -> INDEXING;
            case INDEXING -> COMPLETED;
            default -> throw new IllegalStateException("Terminal stage has no successor");
        };
    }

    /** 判断阶段是否属于需要处理器执行的活动阶段。 */
    public boolean isProcessing() {
        return this != UPLOADED && this != COMPLETED && this != FAILED;
    }
}
