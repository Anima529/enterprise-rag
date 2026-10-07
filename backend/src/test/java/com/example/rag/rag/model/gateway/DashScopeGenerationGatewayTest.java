package com.example.rag.rag.model.gateway;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.config.GenerationProperties;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DashScopeGenerationGatewayTest {

    private HttpServer server;
    private String body;
    private int status = 200;
    private JsonNode request;
    private String path;
    private String baseSuffix = "";

    /** 启动仅供本组用例使用的本地模型 HTTP 服务。 */
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path = exchange.getRequestURI().getPath();
            request = new ObjectMapper().readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only-key");
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    /** 停止本组用例启动的本地模型 HTTP 服务。 */
    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** 构造用例所需的模型或切分配置。 */
    private ChatProperties config(String key) {
        return new ChatProperties(
            "qwen3.8-flash",
            "http://127.0.0.1:" + server.getAddress().getPort() + baseSuffix,
            key,
            "",
            8,
            .2,
            6000,
            64,
            1024,
            2048,
            16000,
            600,
            4,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofMillis(200)
        );
    }

    /** 构造仅包含用例所需消息的生成 Prompt。 */
    private GenerationRequest prompt() {
        return new GenerationRequest("证据回答", List.of(new Message("USER", "问题")), 128);
    }

    /** 验证只流式输出公开正文及实际用量，不输出私有推理。 */
    @Test
    void shouldStreamOnlyPublicTextAndReportActualUsageWithoutPrivateReasoning() {
        body =
            "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"model\":\"qwen3.8-flash\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"中文回答\",\"reasoning_content\":\"private reasoning\"}}]}\n\n" +
            "data: {\"id\":\"test\",\"model\":\"qwen3.8-flash\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}\n\n" +
            "data: [DONE]\n\n";
        List<GenerationDelta> deltas = new DashScopeGenerationGateway(
            config("test-only-key"),
            new GenerationProperties(0.1, 16)
        )
            .generate(prompt())
            .collectList()
            .block(Duration.ofSeconds(4));
        assertThat(deltas.stream().map(GenerationDelta::delta).reduce("", String::concat)).isEqualTo("中文回答");
        assertThat(deltas).anySatisfy(delta -> {
            assertThat(delta.inputTokens()).isEqualTo(20);
            assertThat(delta.outputTokens()).isEqualTo(5);
        });
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(request.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(request.path("model").asText()).isEqualTo("qwen3.8-flash");
        assertThat(request.has("enable_thinking")).isFalse();
    }

    /** 结构化规划显式关闭 Qwen 思考，参数位于请求顶层且不影响回答生成。 */
    @Test
    void shouldDisableQwenThinkingForStructuredStages() {
        body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"{}\"}}]}\n\ndata: [DONE]\n\n";
        try (org.slf4j.MDC.MDCCloseable stage = org.slf4j.MDC.putCloseable("usageStage", "ROUTE")) {
            new DashScopeGenerationGateway(config("test-only-key"), new GenerationProperties(0.1, 16))
                .generate(prompt())
                .blockLast();
        }
        assertThat(request.path("enable_thinking").isBoolean()).isTrue();
        assertThat(request.path("enable_thinking").booleanValue()).isFalse();
    }

    /** 验证配置基础端点仅包含一个版本段。 */
    @ParameterizedTest
    @ValueSource(
        strings = { "", "/compatible-mode", "/compatible-mode/", "/compatible-mode/v1", "/compatible-mode/v1/" }
    )
    void shouldBuildOneVersionSegmentForConfiguredBaseUrl(String suffix) {
        baseSuffix = suffix;
        shouldStreamOnlyPublicTextAndReportActualUsageWithoutPrivateReasoning();
        assertThat(path).isEqualTo(suffix.isEmpty() ? "/v1/chat/completions" : "/compatible-mode/v1/chat/completions");
    }

    /** 验证缺少密钥返回明确错误。 */
    @Test
    void shouldReturnExplicitFailureForMissingKey() {
        assertThatThrownBy(() ->
            new DashScopeGenerationGateway(config(""), new GenerationProperties(0.1, 16)).generate(prompt()).blockLast()
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RAG_503_MODEL_UNAVAILABLE);
        assertThat(request).isNull();
    }

    /** 验证供应方失败脱敏，公开增量不被重试重复。 */
    @Test
    void shouldMapProviderFailureWithoutLeakingBodyOrRetryingPublicDeltas() {
        status = 503;
        body = "{\"error\":{\"message\":\"private provider data\"}}";
        assertThatThrownBy(() ->
            new DashScopeGenerationGateway(config("test-only-key"), new GenerationProperties(0.1, 16))
                .generate(prompt())
                .blockLast()
        )
            .isInstanceOf(BusinessException.class)
            .hasMessage(ErrorCode.RAG_503_MODEL_UNAVAILABLE.message());
    }
}
