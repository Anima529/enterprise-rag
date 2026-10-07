package com.example.rag.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.example.rag.common.api.ApiError;
import com.example.rag.common.api.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 保留业务错误的状态与公开提示，附带请求追踪信息。 */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException exception, HttpServletRequest request) {
        return failure(exception.errorCode(), request);
    }

    /** 将未登录异常转换为统一的认证失败响应。 */
    @ExceptionHandler(NotLoginException.class)
    ResponseEntity<ApiError> unauthenticated(HttpServletRequest request) {
        return failure(ErrorCode.AUTH_401_UNAUTHENTICATED, request);
    }

    /** 将权限或角色不足转换为统一的拒绝访问响应。 */
    @ExceptionHandler({ NotRoleException.class, NotPermissionException.class })
    ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return failure(ErrorCode.AUTH_403_FORBIDDEN, request);
    }

    /** 记录数据库异常并返回脱敏的服务不可用提示。 */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> storage(DataAccessException exception, HttpServletRequest request) {
        log.warn("storage unavailable, exceptionType={}", exception.getClass().getSimpleName());
        return failure(ErrorCode.STORAGE_503_UNAVAILABLE, request);
    }

    /** 记录未预期异常并隐藏内部实现和供应方信息。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        log.error("request failed, exceptionType={}", exception.getClass().getSimpleName());
        return failure(ErrorCode.COMMON_500_INTERNAL, request);
    }

    /** 将 Spring 参数、路由等异常统一为既定错误响应结构。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception exception,
        Object body,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        ErrorCode code = ErrorCode.fromHttpStatus(status.value());
        HttpHeaders errorHeaders = new HttpHeaders();
        errorHeaders.putAll(headers);
        errorHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(error(code, servletRequest), errorHeaders, code.status());
    }

    /** 从业务错误码和请求追踪属性构造公开错误内容。 */
    static ApiError error(ErrorCode code, HttpServletRequest request) {
        return new ApiError(code.name(), code.message(), (String) request.getAttribute(RequestTraceFilter.REQUEST_ID));
    }

    /** 按错误码的 HTTP 状态返回统一错误响应。 */
    private ResponseEntity<ApiError> failure(ErrorCode code, HttpServletRequest request) {
        log.info("request rejected, code={}", code.name());
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(error(code, request));
    }
}
