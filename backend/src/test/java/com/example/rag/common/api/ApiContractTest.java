package com.example.rag.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ApiContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 验证成功响应和字符串错误码符合冻结协议。 */
    @Test
    void shouldSerializeSuccessAndStringErrorCodesAccordingToFrozenContract() throws Exception {
        JsonNode success = mapper.readTree(mapper.writeValueAsString(ApiResponse.success("ok")));
        assertThat(success.get("code").asInt()).isZero();
        JsonNode failure = mapper.readTree(
            mapper.writeValueAsString(new ApiError("KB_403_FORBIDDEN", "无权限", "request-1"))
        );
        assertThat(failure.get("code").isTextual()).isTrue();
        assertThat(failure.get("requestId").asText()).isEqualTo("request-1");
        assertThat(failure.has("data")).isFalse();
    }

    /** 验证 SSE 名称及终态定义与共享协议一致。 */
    @Test
    void shouldMatchSseEventNamesAndTerminalEventsInSharedContract() throws Exception {
        JsonNode contract = mapper.readTree(Path.of("../docs/contracts/v1.json").toFile());
        String[] expected = mapper.convertValue(contract.at("/sse/events"), String[].class);
        assertThat(Arrays.stream(SseEventType.values()).map(SseEventType::wireName)).containsExactly(expected);
        assertThat(
            Arrays.stream(SseEventType.values()).filter(SseEventType::terminal).map(SseEventType::wireName)
        ).containsExactly("done", "error");
    }

    /** 验证拒绝缺少追踪信息或使用数字错误码的响应。 */
    @Test
    void shouldRejectUntraceableErrorsAndNumericErrorCodeStrings() {
        assertThatThrownBy(() -> new ApiError("4001001", "error", "request-1")).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> new ApiError("KB_403_FORBIDDEN", "error", " ")).isInstanceOf(
            IllegalArgumentException.class
        );
    }
}
