package com.example.rag.rag.model.gateway;

import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.rag.model.domain.ModelFailureCode;
import com.example.rag.rag.model.domain.ModelStage;
import com.example.rag.rag.model.gateway.ModelGateways.EmbeddingBatch;
import com.example.rag.rag.model.gateway.ModelGateways.EmbeddingGateway;
import com.example.rag.rag.model.support.EmbeddingVectors;
import com.example.rag.usage.service.UsageService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;

/** 使用 Spring AI 模型抽象，同时保留严格的批次校验和网关统一重试。 */
@Component
public class DashScopeEmbeddingGateway implements EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingGateway.class);
    private final EmbeddingProperties config;
    private final EmbeddingModel model;
    private final Semaphore permits;
    private final MeterRegistry metrics;
    private final UsageService usage;
    private final ThreadLocal<UsageService.Call> activeUsage = new ThreadLocal<>();
    private com.example.rag.governance.service.RedisCostGuard guard;

    /** 每次物理重试独立获取分布式许可；独立 HTTP 单元测试保持原构造方式。 */
    @Autowired
    public void configureGuard(com.example.rag.governance.service.RedisCostGuard guard) {
        guard.validateTimeout(config.timeout());
        this.guard = guard;
    }

    /** 配置向量客户端、超时、并发许可和实际调用指标。 */
    public DashScopeEmbeddingGateway(EmbeddingProperties config, MeterRegistry metrics) {
        this(config, metrics, null);
    }

    /** 将每次实际重试作为独立调用记账，不将批次逻辑重试重复合并。 */
    @Autowired
    public DashScopeEmbeddingGateway(EmbeddingProperties config, MeterRegistry metrics, UsageService usage) {
        this.config = config;
        this.usage = usage;
        this.metrics = metrics;
        permits = new Semaphore(config.maxConcurrency(), true);
        HttpClient client = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(config.timeout());
        String key = config.apiKey().isBlank() ? config.dashscopeApiKey() : config.apiKey();
        model = key.isBlank() ? null : createModel(key, factory);
    }

    /** 按端点、模型和超时配置创建 Embedding 客户端。 */
    private EmbeddingModel createModel(String key, JdkClientHttpRequestFactory factory) {
        OpenAiEndpoints endpoints = OpenAiEndpoints.from(config.baseUrl());
        OpenAiApi api = new OpenAiApi(
            endpoints.baseUrl(),
            new SimpleApiKey(key),
            new LinkedMultiValueMap<>(),
            endpoints.chatPath(),
            endpoints.embeddingPath(),
            RestClient.builder().requestFactory(factory),
            WebClient.builder(),
            new DefaultResponseErrorHandler()
        ) {
            /** 先记录供应方 Token 用量并校验响应结构，避免底层转换记录输入正文。 */
            @Override
            public <T> ResponseEntity<EmbeddingList<OpenAiApi.Embedding>> embeddings(
                OpenAiApi.EmbeddingRequest<T> request
            ) {
                ResponseEntity<EmbeddingList<OpenAiApi.Embedding>> response = super.embeddings(request);
                EmbeddingList<OpenAiApi.Embedding> body = response.getBody();
                long tokens =
                    body == null || body.usage() == null || body.usage().totalTokens() == null
                        ? 0
                        : body.usage().totalTokens();
                if (tokens < 0) {
                    throw invalidBatch();
                }
                // 即使 Spring AI 无法映射异常响应，也保留服务商已报告的用量。
                metrics.counter("rag.embedding.tokens", "model", config.model()).increment(tokens);
                UsageService.Call usageCall = activeUsage.get();
                if (usageCall != null && body != null && body.usage() != null && body.usage().totalTokens() != null) {
                    usageCall.tokens(tokens, null);
                }
                // Spring AI 1.1.2 遇到空响应体会记录输入，并可能访问空的 data/items。
                // 在模型转换前拒绝这些异常结构，避免记录文档正文。
                if (
                    body == null ||
                    body.data() == null ||
                    body
                        .data()
                        .stream()
                        .anyMatch(item -> item == null || item.embedding() == null)
                ) {
                    throw invalidBatch();
                }
                return response;
            }
        };
        RetryTemplate singleAttempt = new RetryTemplate();
        singleAttempt.setRetryPolicy(new NeverRetryPolicy());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(config.model())
            .dimensions(config.dimensions())
            .encodingFormat("float")
            .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options, singleAttempt);
    }

    /** 按输入顺序返回已校验的批量向量。 */
    public List<List<Float>> embed(List<String> texts) {
        return embedBatch(texts).vectors();
    }

    /** 校验批次及资源限制后调用模型，统计实际调用与 Token 用量。 */
    public EmbeddingBatch embedBatch(List<String> texts) {
        if (
            texts == null ||
            texts.isEmpty() ||
            texts.size() > config.batchSize() ||
            texts.stream().anyMatch(text -> text == null || text.isBlank())
        ) {
            throw new IllegalArgumentException("Invalid embedding batch");
        }
        if (model == null) {
            throw new IndexFailure(
                ModelFailureCode.MODEL_503_NOT_CONFIGURED,
                "请在 deploy/.env.local 配置 EMBEDDING_API_KEY 或 DASHSCOPE_API_KEY",
                false
            );
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IndexFailure(ModelFailureCode.MODEL_429_BUSY, "向量化并发已达上限，请稍后重试", true);
            }
            for (int attempt = 0; ; attempt++) {
                try {
                    return call(texts, attempt + 1);
                } catch (IndexFailure failure) {
                    if (!failure.retryable() || attempt >= config.maxRetries()) {
                        throw failure;
                    }
                    Thread.sleep(config.retryBackoff().toMillis() * (attempt + 1));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IndexFailure(ModelFailureCode.MODEL_504_TIMEOUT, "向量化调用已中断", true);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    /** 执行一次模型请求并校验批量向量，记录该次用量与结果指标。 */
    private EmbeddingBatch call(List<String> texts, int callCount) {
        try (
            com.example.rag.governance.service.RedisCostGuard.Permit permit = guard == null
                ? null
                : guard.acquire(config.model())
        ) {
            return callAuthorized(texts, callCount);
        }
    }

    /** 分布式许可覆盖请求与完整批次校验，只有实际发送的模型请求进入账本。 */
    private EmbeddingBatch callAuthorized(List<String> texts, int callCount) {
        long start = System.nanoTime();
        UsageService.Call usageCall = usage == null ? null : usage.begin(ModelStage.EMBEDDING.name(), config.model());
        if (usageCall != null) {
            activeUsage.set(usageCall);
        }
        Throwable usageFailure = null;
        String outcome = "failure";
        try {
            EmbeddingResponse response = model.call(new EmbeddingRequest(texts, null));
            long tokens =
                response == null ||
                response.getMetadata() == null ||
                response.getMetadata().getUsage() == null ||
                response.getMetadata().getUsage().getTotalTokens() == null
                    ? 0
                    : response.getMetadata().getUsage().getTotalTokens();
            if (usageCall != null && tokens > 0) {
                usageCall.tokens(tokens, null);
            }
            if (tokens < 0) {
                throw invalidBatch();
            }
            if (
                response == null ||
                response.getMetadata() == null ||
                !config.model().equals(response.getMetadata().getModel()) ||
                response.getResults() == null ||
                response.getResults().size() != texts.size()
            ) {
                throw invalidBatch();
            }
            List<List<Float>> vectors = new ArrayList<>(Collections.nCopies(texts.size(), null));
            for (Embedding item : response.getResults()) {
                if (
                    item == null ||
                    item.getIndex() == null ||
                    item.getIndex() < 0 ||
                    item.getIndex() >= texts.size() ||
                    vectors.get(item.getIndex()) != null ||
                    item.getOutput() == null
                ) {
                    throw invalidBatch();
                }
                List<Float> vector = new ArrayList<>(item.getOutput().length);
                for (float value : item.getOutput()) {
                    vector.add(value);
                }
                EmbeddingVectors.validate(vector, config.dimensions());
                vectors.set(item.getIndex(), List.copyOf(vector));
            }
            outcome = "success";
            return new EmbeddingBatch(List.copyOf(vectors), tokens, callCount);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retry =
                status == HttpStatus.TOO_MANY_REQUESTS.value() ||
                status == HttpStatus.BAD_GATEWAY.value() ||
                status == HttpStatus.SERVICE_UNAVAILABLE.value() ||
                status == HttpStatus.GATEWAY_TIMEOUT.value();
            IndexFailure failure = new IndexFailure(
                retry ? ModelFailureCode.MODEL_503_UNAVAILABLE : ModelFailureCode.MODEL_502_REJECTED,
                "向量化提供方返回 HTTP " + status,
                retry
            );
            usageFailure = failure;
            throw failure;
        } catch (ResourceAccessException exception) {
            IndexFailure failure = new IndexFailure(ModelFailureCode.MODEL_504_TIMEOUT, "向量化连接失败或超时", true);
            usageFailure = failure;
            throw failure;
        } catch (IndexFailure failure) {
            usageFailure = failure;
            throw failure;
        } catch (RuntimeException exception) {
            IndexFailure failure = invalidBatch();
            usageFailure = failure;
            throw failure;
        } finally {
            activeUsage.remove();
            if (usageCall != null) {
                usageCall.finish("success".equals(outcome), usageFailure);
            }
            long elapsed = System.nanoTime() - start;
            metrics.counter("rag.embedding.calls", "model", config.model(), "outcome", outcome).increment();
            metrics
                .timer("rag.embedding.duration", "model", config.model(), "outcome", outcome)
                .record(elapsed, TimeUnit.NANOSECONDS);
            log.info(
                "embedding provider call, model={}, inputs={}, call={}, outcome={}, elapsedMs={}",
                config.model(),
                texts.size(),
                callCount,
                outcome,
                TimeUnit.NANOSECONDS.toMillis(elapsed)
            );
        }
    }

    /** 构造供应方批次结果不完整或不合法的阶段错误。 */
    private IndexFailure invalidBatch() {
        return new IndexFailure(
            ModelFailureCode.MODEL_502_INVALID_BATCH,
            "向量化返回空、部分、重复、错位或不同模型的结果",
            true
        );
    }
}
