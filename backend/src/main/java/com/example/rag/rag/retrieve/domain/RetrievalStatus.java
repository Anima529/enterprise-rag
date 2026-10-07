package com.example.rag.rag.retrieve.domain;

/** 召回和精排追踪状态，不改变调试响应或故障回退规则。 */
public final class RetrievalStatus {

    public static final String SKIPPED = "SKIPPED";
    public static final String SUCCESS = "SUCCESS";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String FALLBACK_RRF = "FALLBACK_RRF";

    /** 常量容器不参与业务对象实例化。 */
    private RetrievalStatus() {
    }
}
