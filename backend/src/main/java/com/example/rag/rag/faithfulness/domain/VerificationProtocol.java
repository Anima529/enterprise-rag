package com.example.rag.rag.faithfulness.domain;

/** 核验状态、置信度与模型输出字段，字符串契约由服务端严格校验。 */
public final class VerificationProtocol {

    public static final String SUPPORTED = "SUPPORTED";
    public static final String PARTIALLY_SUPPORTED = "PARTIALLY_SUPPORTED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String LOW = "LOW";
    public static final String MEDIUM = "MEDIUM";
    public static final String HIGH = "HIGH";
    public static final String STATUS = "status";
    public static final String COVERAGE = "coverage";
    public static final String UNSUPPORTED_CLAIMS = "unsupportedClaims";
    public static final String CONFLICTING_EVIDENCE = "conflictingEvidence";
    public static final String QUESTION = "question";
    public static final String ANSWER = "answer";
    public static final String EVIDENCE = "evidence";
    public static final String PENDING = "PENDING";
    public static final String NO_EVIDENCE = "NO_EVIDENCE";
    public static final String INVALID_CITATION = "INVALID_CITATION";
    public static final String DISABLED = "DISABLED";
    public static final String INPUT_BUDGET = "INPUT_BUDGET";
    public static final String JUDGE_FAILED = "JUDGE_FAILED";
    public static final String INVALID_RESPONSE = "INVALID_RESPONSE";
    public static final String INVALID_COVERAGE = "INVALID_COVERAGE";
    public static final String INVALID_CLAIM = "INVALID_CLAIM";
    public static final String INCONSISTENT_JUDGE = "INCONSISTENT_JUDGE";
    public static final String JUDGE_UNKNOWN = "JUDGE_UNKNOWN";
    public static final String MISSING_CITATION = "MISSING_CITATION";
    public static final String JUDGED = "JUDGED";

    /** 常量容器不参与业务对象实例化。 */
    private VerificationProtocol() {
    }
}
