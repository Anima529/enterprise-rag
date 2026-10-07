package com.example.rag.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    COMMON_400_VALIDATION(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    COMMON_404_NOT_FOUND(HttpStatus.NOT_FOUND, "接口不存在"),
    COMMON_405_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    COMMON_415_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
    AUTH_401_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "请先登录"),
    AUTH_401_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    AUTH_403_FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行此操作"),
    KB_403_FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该知识库"),
    KB_404_NOT_FOUND(HttpStatus.NOT_FOUND, "知识库不存在"),
    KB_409_DISABLED(HttpStatus.CONFLICT, "知识库已停用"),
    DOC_404_NOT_FOUND(HttpStatus.NOT_FOUND, "文档或版本不存在"),
    DOC_409_DUPLICATE(HttpStatus.CONFLICT, "该知识库已存在相同内容的文档"),
    DOC_409_VERSION_CONFLICT(HttpStatus.CONFLICT, "当前版本已变化或已有版本正在构建，请刷新后重试"),
    CHAT_404_NOT_FOUND(HttpStatus.NOT_FOUND, "会话或请求不存在"),
    CHAT_409_BUSY(HttpStatus.CONFLICT, "会话正在生成回答，请等待或取消"),
    USAGE_409_PRICE_EXISTS(HttpStatus.CONFLICT, "该模型的价格版本已存在，请使用新的版本号"),
    RAG_503_MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "模型服务暂时不可用，请检查模型配置"),
    RAG_429_BUSY(HttpStatus.TOO_MANY_REQUESTS, "问答并发已达上限，请稍后重试"),
    RAG_429_RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "用户或部门请求频率已达上限，请稍后重试"),
    RAG_503_GOVERNANCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "请求治理暂时不可用，已暂停模型调用，请稍后重试"),
    EVAL_404_NOT_FOUND(HttpStatus.NOT_FOUND, "评估数据集或实验不存在"),
    EVAL_409_SNAPSHOT_CHANGED(HttpStatus.CONFLICT, "评估授权、文档或配置已变化，请创建新的实验"),
    EVAL_409_QUEUE_FULL(HttpStatus.CONFLICT, "评估队列已满，请等待现有实验完成"),
    RAG_504_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "问答超时"),
    DOC_409_TASK_NOT_RETRYABLE(HttpStatus.CONFLICT, "仅可重试当前文档版本的失败任务"),
    DOC_413_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小或内容超过上传限制"),
    DOC_415_UNSUPPORTED_FORMAT(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "仅支持 PDF、DOC、DOCX、XLSX、TXT、MD，文件类型必须一致"
    ),
    DOC_422_INVALID_CONTENT(HttpStatus.UNPROCESSABLE_ENTITY, "文件为空、损坏、加密或内容与格式不符；文本须为 UTF-8"),
    DOC_422_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "文档损坏、加密或结构无法解析"),
    DOC_422_EMPTY_TEXT(HttpStatus.UNPROCESSABLE_ENTITY, "文档未提取到可用正文"),
    DOC_422_OCR_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "PDF 无可用文字层，需要 OCR 后重新上传"),
    DOC_413_PARSE_LIMIT(HttpStatus.PAYLOAD_TOO_LARGE, "文档解析超过资源上限"),
    DOC_504_PARSE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "文档解析超时"),
    RAG_403_EMPTY_SCOPE(HttpStatus.FORBIDDEN, "请选择有权访问的知识库"),
    RAG_503_RETRIEVAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "知识库检索暂时不可用"),
    RAG_503_RERANK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "证据精排暂时不可用"),
    STORAGE_503_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "基础设施暂时不可用"),
    COMMON_500_INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "系统暂时不可用");

    private final HttpStatus status;
    private final String message;

    /** 绑定错误码对应的 HTTP 状态和公开提示。 */
    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** 返回业务错误对应的 HTTP 状态。 */
    public HttpStatus status() {
        return status;
    }

    /** 返回允许发送给客户端的错误提示。 */
    public String message() {
        return message;
    }

    /** 将框架 HTTP 错误映射为统一的业务错误码。 */
    public static ErrorCode fromHttpStatus(int status) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null) {
            return COMMON_500_INTERNAL;
        }
        return switch (httpStatus) {
            case BAD_REQUEST, NOT_ACCEPTABLE, UNPROCESSABLE_ENTITY -> COMMON_400_VALIDATION;
            case UNAUTHORIZED -> AUTH_401_UNAUTHENTICATED;
            case FORBIDDEN -> AUTH_403_FORBIDDEN;
            case NOT_FOUND -> COMMON_404_NOT_FOUND;
            case METHOD_NOT_ALLOWED -> COMMON_405_METHOD_NOT_ALLOWED;
            case PAYLOAD_TOO_LARGE -> DOC_413_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> COMMON_415_UNSUPPORTED_MEDIA_TYPE;
            case SERVICE_UNAVAILABLE -> STORAGE_503_UNAVAILABLE;
            default -> COMMON_500_INTERNAL;
        };
    }
}
