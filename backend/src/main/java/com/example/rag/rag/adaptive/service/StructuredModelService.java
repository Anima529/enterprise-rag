package com.example.rag.rag.adaptive.service;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.conversation.domain.ConversationProtocol;
import com.example.rag.rag.model.config.GenerationProperties;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** 使用现有 Generation Gateway 执行有界结构化调用，不开放工具或模型推理。 */
@Service
public class StructuredModelService {

    private final GenerationGateway generation;
    private final ObjectMapper json;
    private final GenerationProperties config;

    /** 复用模型配置与用量埋点，只接收公开文本作为 JSON 输出。 */
    public StructuredModelService(GenerationGateway generation, ObjectMapper json, GenerationProperties config) {
        this.generation = generation;
        this.json = json;
        this.config = config;
    }

    /** 在调用预算内收集严格 JSON；超时取消上游，拒绝解释文本及过量输出。 */
    public JsonNode call(String stage, String instruction, String input, int tokens, Duration timeout) {
        try (MDC.MDCCloseable context = MDC.putCloseable(TraceContextKeys.USAGE_STAGE, stage)) {
            GenerationRequest request = new GenerationRequest(
                instruction,
                List.of(new Message(ConversationProtocol.USER, input)),
                tokens
            );
            String output = generation
                .generate(request)
                .map(delta -> delta.delta() == null ? "" : delta.delta())
                .reduce(new StringBuilder(), (buffer, text) -> {
                    long characterLimit = (long) tokens * config.structuredOutputCharactersPerToken();
                    if (buffer.length() + text.length() > characterLimit) {
                        throw new IllegalArgumentException("Structured output exceeded budget");
                    }
                    return buffer.append(text);
                })
                .map(StringBuilder::toString)
                .block(timeout);
            JsonNode response = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
            if (response == null || !response.isObject()) {
                throw new IllegalArgumentException("Expected JSON object");
            }
            return response;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Invalid structured model response", failure);
        }
    }
}
