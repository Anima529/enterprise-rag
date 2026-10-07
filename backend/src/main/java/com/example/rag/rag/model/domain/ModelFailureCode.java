package com.example.rag.rag.model.domain;

/** 模型网关失败码，仍由既有 IndexFailure 保留重试属性。 */
public final class ModelFailureCode {

    public static final String MODEL_503_NOT_CONFIGURED = "MODEL_503_NOT_CONFIGURED";
    public static final String MODEL_429_BUSY = "MODEL_429_BUSY";
    public static final String MODEL_504_TIMEOUT = "MODEL_504_TIMEOUT";
    public static final String MODEL_503_UNAVAILABLE = "MODEL_503_UNAVAILABLE";
    public static final String MODEL_502_REJECTED = "MODEL_502_REJECTED";
    public static final String MODEL_502_INVALID_BATCH = "MODEL_502_INVALID_BATCH";
    public static final String MODEL_422_DIMENSION_MISMATCH = "MODEL_422_DIMENSION_MISMATCH";
    public static final String MODEL_FAILED = "MODEL_FAILED";

    /** 常量容器不参与业务对象实例化。 */
    private ModelFailureCode() {
    }
}
