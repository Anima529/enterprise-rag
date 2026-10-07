package com.example.rag.common.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 用业务错误码建立异常及允许公开的提示。 */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /** 返回用于映射 HTTP 状态与公开提示的业务错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
