package com.example.rag.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";

    /** 使异步派发继续经过过滤器，恢复请求追踪上下文。 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** 使错误派发继续经过过滤器，保留错误响应的追踪信息。 */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** 复用服务端生成的追踪 ID，并在派发结束后清理 MDC。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = id(request, REQUEST_ID);
        String traceId = id(request, TRACE_ID);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Trace-Id", traceId);
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put(REQUEST_ID, requestId);
        MDC.put(TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    /** 从请求属性复用追踪 ID，首次请求由服务端生成。 */
    private String id(HttpServletRequest request, String name) {
        if (request.getAttribute(name) instanceof String existing) {
            return existing;
        }
        String value = UUID.randomUUID().toString();
        request.setAttribute(name, value);
        return value;
    }
}
