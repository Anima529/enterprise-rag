package com.example.rag.document.domain;

/** 文档上传、索引任务及清理任务的稳定状态和用途。 */
public final class DocumentProtocol {

    public static final String UPLOADED = "UPLOADED";
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String INDEX = "INDEX";
    public static final String REINDEX = "REINDEX";
    public static final String DELETED = "DELETED";

    /** 常量容器不参与业务对象实例化。 */
    private DocumentProtocol() {
    }
}
