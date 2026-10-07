package com.example.rag.rag.chat.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.RequestTraceFilter;
import com.example.rag.common.security.SecurityConfiguration;
import com.example.rag.rag.chat.dto.ChatRequest;
import com.example.rag.rag.chat.service.ChatService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService service;

    /** 配置请求入口所依赖的应用服务。 */
    public ChatController(ChatService service) {
        this.service = service;
    }

    /** 校验问答请求并打开 SSE 回答流。 */
    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter stream(
        @Valid @RequestBody ChatRequest body,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        // 设置 SSE 响应头前先校验并预留资源，使流开始前的错误仍按 REST 协议返回。
        SseEmitter emitter = service.open(
            body,
            (String) request.getAttribute(RequestTraceFilter.REQUEST_ID),
            (String) request.getAttribute(RequestTraceFilter.TRACE_ID)
        );
        request.setAttribute(SecurityConfiguration.CHAT_STREAM_OPENED, Boolean.TRUE);
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return emitter;
    }

    /** 取消指定问答请求。 */
    @PostMapping("/{requestId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String requestId) {
        service.cancel(requestId);
        return ApiResponse.success(null);
    }
}
