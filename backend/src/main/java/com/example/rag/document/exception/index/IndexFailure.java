package com.example.rag.document.exception.index;

/** 仅接收可公开的安全错误文本，不传入模型原始响应、对象键或凭证。 */
public class IndexFailure extends RuntimeException {

    private final String code;
    private final boolean retryable;

    /** 记录公开阶段错误码、提示及自动重试属性。 */
    public IndexFailure(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    /** 返回可公开的索引阶段错误码。 */
    public String code() {
        return code;
    }

    /** 声明该阶段失败是否允许自动重试。 */
    public boolean retryable() {
        return retryable;
    }
}
