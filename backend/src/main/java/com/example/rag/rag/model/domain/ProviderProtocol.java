package com.example.rag.rag.model.domain;

/** 模型供应方请求与响应字段，不作为可调业务配置。 */
public final class ProviderProtocol {

    public static final String MODEL = "model";
    public static final String INPUT = "input";
    public static final String QUERY = "query";
    public static final String DOCUMENTS = "documents";
    public static final String PARAMETERS = "parameters";
    public static final String TOP_N = "top_n";
    public static final String USAGE = "usage";
    public static final String TOTAL_TOKENS = "total_tokens";
    public static final String OUTPUT = "output";
    public static final String RESULTS = "results";
    public static final String INDEX = "index";
    public static final String RELEVANCE_SCORE = "relevance_score";
    public static final String ENABLE_THINKING = "enable_thinking";
    public static final String QWEN_MODEL_PREFIX = "qwen";

    /** 供应方字段以常量访问，不创建协议对象。 */
    private ProviderProtocol() {
    }
}
