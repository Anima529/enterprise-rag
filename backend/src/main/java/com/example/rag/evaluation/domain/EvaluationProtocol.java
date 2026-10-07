package com.example.rag.evaluation.domain;

/** 评估状态、错误和 Judge 版本均以稳定协议值持久化。 */
public final class EvaluationProtocol {

    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String DENIED = "DENIED";
    public static final String ACCESS_NOT_DENIED = "ACCESS_NOT_DENIED";
    public static final String CASE_FAILED = "EVAL_CASE_FAILED";
    public static final String INTERRUPTED = "EVAL_INTERRUPTED";
    public static final String JUDGE_VERSION = "evidence-v1/relevance-v1";
    public static final String EVALUATION = "EVALUATION";
    public static final String DATASET_CREATE = "EVALUATION_DATASET_CREATE";
    public static final String RUN_CREATE = "EVALUATION_RUN_CREATE";
    public static final int LIST_LIMIT = 100;

    /** 协议容器不能实例化。 */
    private EvaluationProtocol() {
    }
}
