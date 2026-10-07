package com.example.rag.rag.model.gateway;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.rag.model.config.RerankerProperties;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class DashScopeRerankerGatewayTest {

    private HttpServer server;
    private ExecutorService workers;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private String response =
        "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.3}]}}";
    private int status = 200;
    private long delay;
    private final AtomicInteger calls = new AtomicInteger();

    /** 为精排网关建立独立 HTTP 协议替身，不连接真实供应商。 */
    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/rerank", exchange -> {
            calls.incrementAndGet();
            request.set(json.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    /** 用例结束关闭随机 HTTP 端口及所有测试处理线程。 */
    @AfterEach
    void cleanup() {
        server.stop(0);
        workers.shutdownNow();
    }

    /** 检查 Qwen 原生接口请求及候选对应关系，不发送新模型未支持的 return_documents。 */
    @Test
    void shouldUseNativeCrossEncoderContractAndPreserveIndexes() {
        assertThat(gateway("test-only-key", "").rerank("年假", List.of("报销材料", "年假五天")))
            .extracting(ModelGateways.RerankScore::candidateIndex)
            .containsExactly(1, 0);
        assertThat(request.get().path("input").path("query").asText()).isEqualTo("年假");
        assertThat(request.get().path("model").asText()).isEqualTo("qwen3.7-text-rerank");
        assertThat(request.get().path("parameters").path("top_n").asInt()).isEqualTo(2);
        assertThat(request.get().path("parameters").has("return_documents")).isFalse();
        assertThat(calls).hasValue(1);
    }

    /** 缺少密钥或输入超预算时不发起收费调用，备用密钥可用。 */
    @Test
    void shouldBoundInputsAndUseFallbackKey() {
        assertThatThrownBy(() -> gateway("", "").rerank("年假", List.of("内容"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> gateway("test-only", "").rerank("年假", Collections.nCopies(41, "内容"))).isInstanceOf(
            BusinessException.class
        );
        assertThat(calls).hasValue(0);
        assertThat(gateway("", "fallback-test-only").rerank("年假", List.of("报销材料", "年假五天"))).hasSize(2);
    }

    /** 部分、越界、重复和非数值返回不能进入后续生成证据。 */
    @Test
    void shouldRejectMalformedPartialAndOutOfRangeScores() {
        for (String invalid : List.of(
            "[]",
            "[{\"index\":99,\"relevance_score\":0.9}]",
            "[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]",
            "[{\"index\":0,\"relevance_score\":\"0.9\"},{\"index\":1,\"relevance_score\":0.2}]",
            "[{\"index\":0,\"relevance_score\":1.1},{\"index\":1,\"relevance_score\":0.2}]"
        )) {
            response = "{\"output\":{\"results\":" + invalid + "}}";
            assertThatThrownBy(() -> gateway("test-only", "").rerank("年假", List.of("材料", "五天"))).isInstanceOf(
                BusinessException.class
            );
        }
    }

    /** HTTP 失败和整个调用超时均脱敏且不重试，不突破精排总预算。 */
    @Test
    void shouldSanitizeHttpFailuresAndTimeoutWithoutRetry() {
        status = 429;
        response = "provider private detail secret-key";
        assertThatThrownBy(() -> gateway("test-only", "").rerank("年假", List.of("材料", "五天")))
            .isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.errorCode()).isEqualTo(ErrorCode.RAG_503_RERANK_UNAVAILABLE)
            )
            .hasMessageNotContaining("secret-key");
        delay = 1500;
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway("test-only", "").rerank("年假", List.of("材料", "五天"))).isInstanceOf(
            BusinessException.class
        );
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1200);
        assertThat(calls).hasValue(2);
    }

    /** 使用 800ms 合同建立网关实例，所有请求仅发送至本地测试端口。 */
    private DashScopeRerankerGateway gateway(String key, String fallbackKey) {
        RerankerProperties config = new RerankerProperties(
            "qwen3.7-text-rerank",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/rerank",
            key,
            fallbackKey,
            Duration.ofMillis(500),
            Duration.ofMillis(800),
            2,
            40,
            5,
            4000,
            120000
        );
        return new DashScopeRerankerGateway(config, new ChunkTokenizer(), json);
    }
}
