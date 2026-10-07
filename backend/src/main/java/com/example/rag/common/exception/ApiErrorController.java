package com.example.rag.common.exception;

import com.example.rag.common.api.ApiError;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiErrorController implements ErrorController {

    /** 将容器错误转换为统一接口错误响应。 */
    @RequestMapping("/error")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int httpStatus = status instanceof Integer value ? value : HttpStatus.INTERNAL_SERVER_ERROR.value();
        ErrorCode code = ErrorCode.fromHttpStatus(httpStatus);
        return ResponseEntity.status(code.status()).body(GlobalExceptionHandler.error(code, request));
    }
}
