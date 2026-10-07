package com.example.rag.governance.service;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.rag.adaptive.config.AdaptiveProperties;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.faithfulness.config.FaithfulnessProperties;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.rag.model.config.GenerationProperties;
import com.example.rag.rag.model.config.RerankerProperties;
import com.example.rag.rag.prompt.service.PromptCatalog;
import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

/** 以白名单保留可复现实验配置，禁止直接序列化包含 API Key 的模型配置对象。 */
@Component
public class RagConfigurationSnapshot {

    private final Map<String, Object> snapshot;
    private final String digest;

    /** 固定所有影响检索、生成、核验的参数与完整 Prompt，端点仅保存摘要。 */
    public RagConfigurationSnapshot(
        RetrievalProperties retrieval,
        ChunkProperties chunk,
        ChatProperties chat,
        EmbeddingProperties embedding,
        RerankerProperties reranker,
        AdaptiveProperties adaptive,
        FaithfulnessProperties faithfulness,
        GenerationProperties generation,
        PromptCatalog prompts,
        ObjectMapper json
    ) throws JsonProcessingException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("retrieval", retrieval);
        values.put("chunk", chunk);
        values.put("adaptive", adaptive);
        values.put("faithfulness", faithfulness);
        values.put("generation", generation);
        values.put(
            "chat",
            Map.of(
                "model",
                chat.model(),
                "contextTokens",
                chat.contextTokens(),
                "maxOutputTokens",
                chat.maxOutputTokens(),
                "minSimilarity",
                chat.minSimilarity(),
                "questionTokens",
                chat.questionTokens(),
                "endpointHash",
                ScopeFingerprint.digest(chat.baseUrl())
            )
        );
        values.put(
            "embedding",
            Map.of(
                "model",
                embedding.model(),
                "dimensions",
                embedding.dimensions(),
                "endpointHash",
                ScopeFingerprint.digest(embedding.baseUrl())
            )
        );
        values.put(
            "reranker",
            Map.of(
                "model",
                reranker.model(),
                "topN",
                reranker.topN(),
                "timeout",
                reranker.timeout(),
                "endpointHash",
                ScopeFingerprint.digest(reranker.endpoint())
            )
        );
        values.put(
            "prompts",
            Map.of(
                "answer",
                prompts.answer(),
                "rewrite",
                prompts.rewrite(),
                "router",
                prompts.router(),
                "decompose",
                prompts.decompose(adaptive.maxSubqueries()),
                "hyde",
                prompts.hyde(),
                "faithfulness",
                prompts.faithfulness(),
                "relevance",
                prompts.relevance()
            )
        );
        snapshot = Collections.unmodifiableMap(values);
        digest = ScopeFingerprint.digest(
            json
                .writer()
                .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(snapshot)
        );
    }

    /** 返回白名单配置，不包含认证凭据。 */
    public Map<String, Object> values() {
        return snapshot;
    }

    /** 返回缓存配置版本，部署修改任何入模规则后旧缓存自然失效。 */
    public String digest() {
        return digest;
    }
}
