package com.example.rag.rag.model.gateway;

import java.util.List;
import reactor.core.publisher.Flux;

/** 与模型服务商无关的调用契约，实现由索引和聊天模块提供。 */
public final class ModelGateways {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private ModelGateways() {
    }

    public interface GenerationGateway {
        /** 按请求的 Prompt 返回公开文本与用量增量流，失败时终止流。 */
        Flux<GenerationDelta> generate(GenerationRequest request);
    }

    public interface EmbeddingGateway {
        /** 按输入顺序返回每条文本对应的向量。 */
        List<List<Float>> embed(List<String> texts);

        /** 按输入顺序返回批量向量及实际调用用量。 */
        default EmbeddingBatch embedBatch(List<String> texts) {
            return new EmbeddingBatch(embed(texts), 0, 1);
        }
    }

    public record EmbeddingBatch(List<List<Float>> vectors, long totalTokens, int callCount) {}

    public interface RerankerGateway {
        /** 对查询候选文本返回重排分数，保持候选对应关系。 */
        List<RerankScore> rerank(String query, List<String> candidates);
    }

    public record GenerationRequest(String systemPrompt, List<Message> messages, int maxOutputTokens) {}

    public record Message(String role, String content) {}

    public record GenerationDelta(String delta, boolean completed, Long inputTokens, Long outputTokens) {
        /** 创建不含用量信息的文本或完成增量。 */
        public GenerationDelta(String delta, boolean completed) {
            this(delta, completed, null, null);
        }
    }

    public record RerankScore(int candidateIndex, double score) {}
}
