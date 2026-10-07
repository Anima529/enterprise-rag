package com.example.rag.rag.retrieve.service;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.chat.service.ContextBuilder;
import com.example.rag.rag.retrieve.controller.RetrievalDebugController;
import org.springframework.stereotype.Service;

@Service
public class RetrievalDebugService {

    private final PermissionScopeService permissions;
    private final HybridRetrievalService retrieval;
    private final ContextBuilder contexts;
    private final ChunkTokenizer tokenizer;
    private final ChatProperties config;
    private final com.example.rag.conversation.mapper.ConversationMapper messages;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    /** 配置诊断的权限、问题预算、检索与实际上下文构建依赖。 */
    public RetrievalDebugService(
        PermissionScopeService permissions,
        HybridRetrievalService retrieval,
        ContextBuilder contexts,
        ChunkTokenizer tokenizer,
        ChatProperties config,
        com.example.rag.conversation.mapper.ConversationMapper messages,
        com.fasterxml.jackson.databind.ObjectMapper json
    ) {
        this.permissions = permissions;
        this.retrieval = retrieval;
        this.contexts = contexts;
        this.tokenizer = tokenizer;
        this.config = config;
        this.messages = messages;
        this.json = json;
    }

    /** 在管理员授权范围内运行真实检索，不调用回答模型或保存聊天消息。 */
    public RetrievalDebugController.Response inspect(RetrievalDebugController.Request request) {
        if (tokenizer.count(request.query()) > config.questionTokens()) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        RetrievalScope scope = permissions.resolve(request.knowledgeBaseIds());
        HybridRetrievalService.Result retrieved = retrieval.retrieve(scope, request.query(), request.complexity());
        ContextBuilder.Context context = contexts.buildCandidates(request.query(), retrieved.selected());
        return new RetrievalDebugController.Response(retrieved, context);
    }

    /** 管理员读取本人请求的自动路线追踪，撤销权限后不再暴露历史候选正文。 */
    public com.fasterxml.jackson.databind.JsonNode trace(String requestId) {
        long userId = permissions.currentUser().id();
        com.example.rag.conversation.mapper.ConversationMapper.RetrievalTrace trace = messages.trace(requestId, userId);
        if (trace == null) {
            throw new BusinessException(ErrorCode.CHAT_404_NOT_FOUND);
        }
        try {
            java.util.List<Long> ids = json.readValue(
                trace.scopeJson(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {}
            );
            permissions.resolve(ids);
            return json.readTree(trace.traceJson());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Invalid retrieval trace", failure);
        }
    }
}
