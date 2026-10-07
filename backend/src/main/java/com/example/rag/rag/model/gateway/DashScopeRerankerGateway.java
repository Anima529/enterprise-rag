package com.example.rag.rag.model.gateway;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.rag.model.config.RerankerProperties;
import com.example.rag.rag.model.domain.ModelStage;
import com.example.rag.rag.model.domain.ProviderProtocol;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.usage.service.UsageService;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 调用独立 Cross-Encoder HTTP 接口；单次预算包含响应体读取，不重试。 */
@Component
public class DashScopeRerankerGateway implements RerankerGateway {

    private final RerankerProperties config;
    private final ChunkTokenizer tokenizer;
    private final ObjectMapper json;
    private final HttpClient client;
    private final Semaphore permits;
    private final UsageService usage;
    private com.example.rag.governance.service.RedisCostGuard guard;

    /** 精排同样受分布式许可限制，故障由检索层安全回退授权 RRF。 */
    @Autowired
    public void configureGuard(com.example.rag.governance.service.RedisCostGuard guard) {
        guard.validateTimeout(config.timeout());
        this.guard = guard;
    }

    /** 配置全进程共享并发与精排网络连接预算。 */
    public DashScopeRerankerGateway(RerankerProperties config, ChunkTokenizer tokenizer, ObjectMapper json) {
        this(config, tokenizer, json, null);
    }

    /** 注入实际精排调用记录，未报告 Token 时不创建虚构计量。 */
    @Autowired
    public DashScopeRerankerGateway(
        RerankerProperties config,
        ChunkTokenizer tokenizer,
        ObjectMapper json,
        UsageService usage
    ) {
        this.config = config;
        this.usage = usage;
        this.tokenizer = tokenizer;
        this.json = json;
        client = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        permits = new Semaphore(config.maxConcurrency());
    }

    /** 校验输入与完整响应的索引白名单；失败交由检索层回退已授权的 RRF。 */
    @Override
    public List<RerankScore> rerank(String query, List<String> candidates) {
        try (
            com.example.rag.governance.service.RedisCostGuard.Permit permit = guard == null
                ? null
                : guard.acquire(config.model())
        ) {
            return rerankAuthorized(query, candidates);
        }
    }

    /** 完成输入和供应方结果校验后返回排序，模型 IO 不占用数据库事务。 */
    private List<RerankScore> rerankAuthorized(String query, List<String> candidates) {
        String key = config.apiKey().isBlank() ? config.dashscopeApiKey() : config.apiKey();
        if (key.isBlank() || !permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
        }
        CompletableFuture<HttpResponse<String>> response = null;
        UsageService.Call usageCall = null;
        boolean success = false;
        try {
            validateInput(query, candidates);
            String body = json.writeValueAsString(
                Map.of(
                    ProviderProtocol.MODEL,
                    config.model(),
                    ProviderProtocol.INPUT,
                    Map.of(ProviderProtocol.QUERY, query, ProviderProtocol.DOCUMENTS, candidates),
                    ProviderProtocol.PARAMETERS,
                    Map.of(ProviderProtocol.TOP_N, Math.min(config.topN(), candidates.size()))
                )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            usageCall = usage == null ? null : usage.begin(ModelStage.RERANK.name(), config.model());
            response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> completed = response.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (completed.statusCode() != HttpStatus.OK.value()) {
                throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
            }
            JsonNode bodyNode = json.readTree(completed.body());
            if (
                usageCall != null &&
                bodyNode.path(ProviderProtocol.USAGE).path(ProviderProtocol.TOTAL_TOKENS).isIntegralNumber()
            ) {
                usageCall.tokens(
                    bodyNode.path(ProviderProtocol.USAGE).path(ProviderProtocol.TOTAL_TOKENS).longValue(),
                    null
                );
            }
            List<RerankScore> scores = scores(bodyNode, candidates.size());
            success = true;
            return scores;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
        } catch (Exception failure) {
            // 不记录供应商正文、Authorization 或候选文本，公开错误统一脱敏。
            throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
        } finally {
            if (usageCall != null) {
                usageCall.finish(success, success ? null : new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE));
            }
            if (response != null && !response.isDone()) {
                response.cancel(true);
            }
            permits.release();
        }
    }

    /** 按 query 重复计入每个候选的方式限制请求 Token，防止精排成本无界增长。 */
    private void validateInput(String query, List<String> candidates) {
        int queryTokens = tokenizer.count(query);
        long totalTokens = (long) queryTokens * candidates.size();
        if (
            query.isBlank() ||
            queryTokens > config.maxTextTokens() ||
            candidates.isEmpty() ||
            candidates.size() > config.maxCandidates()
        ) {
            throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
        }
        for (String candidate : candidates) {
            int tokens = tokenizer.count(candidate);
            if (candidate.isBlank() || tokens > config.maxTextTokens()) {
                throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
            }
            totalTokens += tokens;
        }
        if (totalTokens > config.maxRequestTokens()) {
            throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
        }
    }

    /** 要求供应商返回完整 topN、唯一有效索引与有限分数，拒绝部分成功混入证据。 */
    private List<RerankScore> scores(JsonNode body, int candidates) {
        JsonNode results = body.path(ProviderProtocol.OUTPUT).path(ProviderProtocol.RESULTS);
        if (!results.isArray() || results.size() != Math.min(candidates, config.topN())) {
            throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
        }
        Set<Integer> indexes = new HashSet<>();
        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode result : results) {
            JsonNode index = result.path(ProviderProtocol.INDEX);
            JsonNode score = result.path(ProviderProtocol.RELEVANCE_SCORE);
            if (
                !index.canConvertToInt() ||
                !index.isIntegralNumber() ||
                index.intValue() < 0 ||
                index.intValue() >= candidates ||
                !indexes.add(index.intValue()) ||
                !score.isNumber() ||
                !Double.isFinite(score.doubleValue()) ||
                score.doubleValue() < 0 ||
                score.doubleValue() > 1
            ) {
                throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
            }
            scores.add(new RerankScore(index.intValue(), score.doubleValue()));
        }
        return List.copyOf(scores);
    }
}
