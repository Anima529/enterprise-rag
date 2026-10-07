package com.example.rag.rag.model.gateway;

import java.net.URI;

/** 同时支持服务根地址和包含版本路径的 OpenAI 兼容地址。 */
record OpenAiEndpoints(String baseUrl, String chatPath, String embeddingPath) {
    /** 规范化兼容 OpenAI 的基础端点，避免重复追加版本路径。 */
    static OpenAiEndpoints from(String configuredBaseUrl) {
        String baseUrl = configuredBaseUrl.replaceAll("/+$", "");
        String prefix = URI.create(baseUrl).getPath().endsWith("/v1") ? "" : "/v1";
        return new OpenAiEndpoints(baseUrl, prefix + "/chat/completions", prefix + "/embeddings");
    }
}
