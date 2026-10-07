package com.example.rag.common.api;

/** 请求追踪和用量阶段共享的 MDC Key，保持日志与异步上下文协议。 */
public final class TraceContextKeys {

    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";
    public static final String USAGE_STAGE = "usageStage";
    public static final String USAGE_DOCUMENT_ID = "usageDocumentId";

    /** 追踪键通过静态成员访问，无需创建实例。 */
    private TraceContextKeys() {
    }
}
