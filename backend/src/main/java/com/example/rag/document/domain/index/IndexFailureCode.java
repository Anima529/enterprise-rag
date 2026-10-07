package com.example.rag.document.domain.index;

/** 索引内部错误码，不扩展对外 API 错误枚举。 */
public final class IndexFailureCode {

    public static final String DOC_503_EXECUTOR_BUSY = "DOC_503_EXECUTOR_BUSY";
    public static final String DOC_504_TASK_TIMEOUT = "DOC_504_TASK_TIMEOUT";
    public static final String DOC_409_SOURCE_UNAVAILABLE = "DOC_409_SOURCE_UNAVAILABLE";
    public static final String DOC_503_STAGE_UNAVAILABLE = "DOC_503_STAGE_UNAVAILABLE";
    public static final String DOC_500_INDEX_FAILED = "DOC_500_INDEX_FAILED";
    public static final String DOC_409_PARSE_REQUIRED = "DOC_409_PARSE_REQUIRED";
    public static final String DOC_413_CHUNK_LIMIT = "DOC_413_CHUNK_LIMIT";
    public static final String DOC_504_CHUNK_TIMEOUT = "DOC_504_CHUNK_TIMEOUT";

    public static final String DOC_422_EMPTY_CHUNKS = "DOC_422_EMPTY_CHUNKS";

    public static final String DOC_409_EMBEDDING_INCONSISTENT = "DOC_409_EMBEDDING_INCONSISTENT";

    /** 常量容器不参与业务对象实例化。 */
    private IndexFailureCode() {
    }
}
