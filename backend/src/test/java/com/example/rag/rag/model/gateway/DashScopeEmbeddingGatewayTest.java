package com.example.rag.rag.model.gateway;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.rag.model.gateway.ModelGateways.EmbeddingBatch;
import com.example.rag.rag.model.support.EmbeddingVectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

class DashScopeEmbeddingGatewayTest {

    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private IntFunction<String> response = n -> body("qwen3.7-text-embedding-flash", List.of(item(0, 1024, 1)));
    private IntFunction<Integer> status = n -> 200;
    private int delayMs;
    private ExecutorService executor;

    /** 启动仅供本组用例使用的本地模型 HTTP 服务。 */
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            int n = calls.incrementAndGet();
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                requests.add(json.readTree(exchange.getRequestBody()));
                paths.add(exchange.getRequestURI().getPath());
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                byte[] bytes = response.apply(n).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.apply(n), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }

    /** 停止本组用例启动的本地模型 HTTP 服务。 */
    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
        metrics.close();
    }

    /** 构造用例所需的模型或切分配置。 */
    static EmbeddingProperties config(String baseUrl, String apiKey, String fallback, int retries, Duration timeout) {
        return new EmbeddingProperties(
            "qwen3.7-text-embedding-flash",
            1024,
            baseUrl,
            apiKey,
            fallback,
            10,
            2,
            Duration.ofSeconds(1),
            timeout,
            retries,
            Duration.ofMillis(1),
            2,
            100
        );
    }

    /** 按测试配置创建向量模型网关。 */
    private DashScopeEmbeddingGateway gateway(int retries, Duration timeout) {
        return new DashScopeEmbeddingGateway(
            config("http://127.0.0.1:" + server.getAddress().getPort(), "", "test-secret", retries, timeout),
            metrics
        );
    }

    /** 验证配置模型发送正确且乱序批次按索引恢复。 */
    @Test
    void shouldSendConfiguredModelAndRestoreOutOfOrderBatchUsingIndices() {
        response = n -> body("qwen3.7-text-embedding-flash", List.of(item(1, 1024, 2), item(0, 1024, 1)));
        EmbeddingBatch result = gateway(0, Duration.ofSeconds(2)).embedBatch(List.of("中文", "code"));
        assertThat(result.vectors().getFirst().getFirst()).isEqualTo(1);
        assertThat(result.vectors().getLast().getFirst()).isEqualTo(2);
        assertThat(result.totalTokens()).isEqualTo(7);
        assertThat(result.callCount()).isEqualTo(1);
        assertThat(requests.getFirst().path("model").asText()).isEqualTo("qwen3.7-text-embedding-flash");
        assertThat(requests.getFirst().path("dimensions").asInt()).isEqualTo(1024);
        assertThat(requests.getFirst().path("encoding_format").asText()).isEqualTo("float");
        assertThat(metrics.get("rag.embedding.calls").tag("outcome", "success").counter().count()).isEqualTo(1);
    }

    /** 验证配置模型及基础端点只追加一个版本段。 */
    @ParameterizedTest
    @ValueSource(
        strings = { "", "/compatible-mode", "/compatible-mode/", "/compatible-mode/v1", "/compatible-mode/v1/" }
    )
    void shouldUseConfiguredModelAndBuildOneVersionSegment(String suffix) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + suffix;
        EmbeddingBatch result = new DashScopeEmbeddingGateway(
            config(baseUrl, "test-only-key", "", 0, Duration.ofSeconds(2)),
            metrics
        ).embedBatch(List.of("中文"));
        String expected = suffix.isEmpty() ? "/v1/embeddings" : "/compatible-mode/v1/embeddings";
        assertThat(paths).containsExactly(expected);
        assertThat(requests.getFirst().path("model").asText()).isEqualTo("qwen3.7-text-embedding-flash");
        assertThat(result.vectors()).hasSize(1);
    }

    /** 验证非法供应方向量结果被拒绝。 */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "empty",
            "partial",
            "duplicate",
            "range",
            "model",
            "dimension",
            "zero",
            "nonfinite",
            "missing-index",
            "null-data",
            "null-item",
            "null-vector",
            "negative-tokens",
            "malformed",
        }
    )
    void shouldRejectInvalidProviderResults(String failure) {
        response = n ->
            switch (failure) {
                case "empty" -> "null";
                case "partial" -> body("qwen3.7-text-embedding-flash", List.of(item(0, 1024, 1)));
                case "duplicate" -> body("qwen3.7-text-embedding-flash", List.of(item(0, 1024, 1), item(0, 1024, 2)));
                case "range" -> body("qwen3.7-text-embedding-flash", List.of(item(0, 1024, 1), item(2, 1024, 2)));
                case "model" -> body("other-model", List.of(item(0, 1024, 1), item(1, 1024, 2)));
                case "dimension" -> body("qwen3.7-text-embedding-flash", List.of(item(0, 3, 1), item(1, 1024, 2)));
                case "zero" -> body("qwen3.7-text-embedding-flash", List.of(item(0, 1024, 0), item(1, 1024, 2)));
                case "nonfinite" -> body(
                    "qwen3.7-text-embedding-flash",
                    List.of(item(0, 1024, Float.NaN), item(1, 1024, 2))
                );
                case "missing-index" -> {
                    Map<String, Object> missing = new java.util.HashMap<>(item(0, 1024, 1));
                    missing.remove("index");
                    yield body("qwen3.7-text-embedding-flash", List.of(missing, item(1, 1024, 2)));
                }
                case "null-data" -> "{\"model\":\"qwen3.7-text-embedding-flash\",\"data\":null,\"usage\":{\"total_tokens\":7}}";
                case "null-item" -> body(
                    "qwen3.7-text-embedding-flash",
                    java.util.Arrays.asList(null, item(1, 1024, 2))
                );
                case "null-vector" -> {
                    Map<String, Object> missing = new java.util.HashMap<>(item(0, 1024, 1));
                    missing.put("embedding", null);
                    yield body("qwen3.7-text-embedding-flash", List.of(missing, item(1, 1024, 2)));
                }
                case "negative-tokens" -> body(
                    "qwen3.7-text-embedding-flash",
                    List.of(item(0, 1024, 1), item(1, 1024, 2)),
                    -1
                );
                default -> "{invalid-json test-secret}";
            };
        assertThatThrownBy(() -> gateway(0, Duration.ofSeconds(2)).embedBatch(List.of("a", "b")))
            .isInstanceOf(IndexFailure.class)
            .hasMessageNotContaining("test-secret");
        assertThat(calls).hasValue(1);
        if (!List.of("empty", "malformed", "negative-tokens").contains(failure)) {
            assertThat(metrics.get("rag.embedding.tokens").counter().count()).isEqualTo(7);
        }
    }

    /** 验证空响应失败且日志不包含输入正文。 */
    @Test
    void shouldRejectEmptyProviderBodyWithoutLoggingInputText() {
        response = n -> "null";
        String text = "confidential-embedding-input-test-marker";
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiEmbeddingModel.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            assertThatThrownBy(() -> gateway(0, Duration.ofSeconds(2)).embed(List.of(text)))
                .isInstanceOf(IndexFailure.class)
                .extracting("code")
                .isEqualTo("MODEL_502_INVALID_BATCH");
            assertThat(events.list).noneMatch(event -> event.getFormattedMessage().contains(text));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    /** 验证仅临时 HTTP 错误重试并统计实际调用次数。 */
    @Test
    void shouldRetryOnlyTemporaryHttpErrorsAndCountActualCalls() {
        status = n -> n == 1 ? 429 : 200;
        EmbeddingBatch result = gateway(2, Duration.ofSeconds(2)).embedBatch(List.of("a"));
        assertThat(result.callCount()).isEqualTo(2);
        assertThat(calls).hasValue(2);
        assertThat(metrics.get("rag.embedding.calls").tag("outcome", "failure").counter().count()).isEqualTo(1);
    }

    /** 验证不完整批次重试有界，认证失败不重试。 */
    @Test
    void shouldBoundPartialBatchRetriesAndRejectAuthenticationWithoutRetry() {
        response = n -> body("qwen3.7-text-embedding-flash", List.of());
        assertThatThrownBy(() -> gateway(2, Duration.ofSeconds(2)).embed(List.of("a"))).isInstanceOf(
            IndexFailure.class
        );
        assertThat(calls).hasValue(3);
        status = n -> 401;
        assertThatThrownBy(() -> gateway(2, Duration.ofSeconds(2)).embed(List.of("a")))
            .isInstanceOf(IndexFailure.class)
            .extracting("retryable")
            .isEqualTo(false);
        assertThat(calls).hasValue(4);
    }

    /** 验证模型超时与并发上限。 */
    @Test
    void shouldTimeOutAndLimitConcurrentCalls() throws Exception {
        delayMs = 250;
        assertThatThrownBy(() -> gateway(0, Duration.ofMillis(30)).embed(List.of("a")))
            .isInstanceOf(IndexFailure.class)
            .extracting("code")
            .isEqualTo("MODEL_504_TIMEOUT");
        // 等待超时的测试服务处理器退出后，再测量新网关的并发数。
        Thread.sleep(300);
        maxActive.set(0);
        delayMs = 60;
        DashScopeEmbeddingGateway shared = gateway(0, Duration.ofSeconds(2));
        List<Future<List<List<Float>>>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(executor.submit(() -> shared.embed(List.of("a"))));
        }
        for (Future<List<List<Float>>> future : futures) {
            assertThat(future.get(3, TimeUnit.SECONDS)).hasSize(1);
        }
        assertThat(maxActive.get()).isBetween(1, 2);
    }

    /** 验证缺少密钥、超大批次和非有限向量在 HTTP 之前被拒绝。 */
    @Test
    void shouldRejectMissingKeyOversizedBatchAndNonFiniteVectorsWithoutHttp() {
        DashScopeEmbeddingGateway missing = new DashScopeEmbeddingGateway(
            config("http://127.0.0.1:1", "", "", 0, Duration.ofSeconds(1)),
            metrics
        );
        assertThatThrownBy(() -> missing.embed(List.of("a")))
            .isInstanceOf(IndexFailure.class)
            .extracting("code")
            .isEqualTo("MODEL_503_NOT_CONFIGURED");
        assertThatThrownBy(() ->
            gateway(0, Duration.ofSeconds(1)).embed(java.util.Collections.nCopies(11, "a"))
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingVectors.validate(List.of(Float.NaN), 1)).isInstanceOf(IndexFailure.class);
        assertThat(calls).hasValue(0);
    }

    /** 构造带索引的供应方向量结果。 */
    private Map<String, Object> item(int index, int dimensions, float first) {
        float[] values = new float[dimensions];
        values[0] = first;
        return Map.of("index", index, "embedding", values, "object", "embedding");
    }

    /** 构造或发送用例指定的模型响应内容。 */
    private String body(String model, List<Map<String, Object>> items) {
        return body(model, items, 7);
    }

    /** 构造或发送用例指定的模型响应内容。 */
    private String body(String model, List<Map<String, Object>> items, int tokens) {
        try {
            return json.writeValueAsString(
                Map.of("model", model, "data", items, "usage", Map.of("total_tokens", tokens), "object", "list")
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
