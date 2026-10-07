package com.example.rag.rag.adaptive.domain;

/** 规划输出字段、原因码及回退码，保留现有 JSON 与追踪协议。 */
public final class AdaptiveProtocol {

    public static final String QUERY = "query";
    public static final String ROUTE = "route";
    public static final String QUERIES = "queries";
    public static final String PASSAGE = "passage";
    public static final String HISTORY = "history";
    public static final String QUESTION = "question";
    public static final String CONFIGURED_ROUTE = "CONFIGURED_ROUTE";
    public static final String DISABLED = "DISABLED";
    public static final String NONE = "NONE";
    public static final String REWRITE_FAILED = "REWRITE_FAILED";
    public static final String ROUTER_FAILED = "ROUTER_FAILED";
    public static final String COMPLEX_RETRIEVAL_FAILED = "COMPLEX_RETRIEVAL_FAILED";
    public static final String COMPLEX_PLAN_FAILED = "COMPLEX_PLAN_FAILED";
    public static final String FALLBACK_STANDARD = "FALLBACK_STANDARD";
    public static final String MODEL_REASON_PREFIX = "MODEL_";

    /** 常量容器不参与业务对象实例化。 */
    private AdaptiveProtocol() {
    }
}
