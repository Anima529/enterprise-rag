package com.example.rag.rag.retrieve.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.rag.chat.service.ContextBuilder;
import com.example.rag.rag.retrieve.config.RetrievalProperties.Complexity;
import com.example.rag.rag.retrieve.service.HybridRetrievalService;
import com.example.rag.rag.retrieve.service.RetrievalDebugService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 管理员调试入口仍先计算范围，不提供绕过授权的全库候选接口。 */
@RestController
@RequestMapping("/api/v1/admin/retrieval")
public class RetrievalDebugController {

    /** 在重新鉴权后读取本人聊天请求的 Rewrite、路线、回退及各路候选。 */
    @GetMapping("/traces/{requestId}")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> trace(@PathVariable String requestId) {
        com.fasterxml.jackson.databind.JsonNode trace = service.trace(requestId);
        return ApiResponse.success(trace);
    }

    public record Request(
        @NotBlank @Size(max = 4000) String query,
        @NotEmpty @Size(max = 20) List<@Positive @NotNull Long> knowledgeBaseIds,
        @NotNull Complexity complexity
    ) {}

    public record Response(HybridRetrievalService.Result retrieval, ContextBuilder.Context context) {}

    private final RetrievalDebugService service;

    /** 配置管理员检索诊断所需的授权、预算和证据构建依赖。 */
    public RetrievalDebugController(RetrievalDebugService service) {
        this.service = service;
    }

    /** 返回两路 rank、分数、回退状态与实际入模 Parent，接口由管理员拦截器保护。 */
    @PostMapping("/debug")
    public ApiResponse<Response> debug(@Valid @RequestBody Request request) {
        Response response = service.inspect(request);
        return ApiResponse.success(response);
    }
}
