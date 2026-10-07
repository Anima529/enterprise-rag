package com.example.rag.rag.adaptive.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.rag.rag.model.config.GenerationProperties;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class StructuredModelServiceTest {

    private final GenerationGateway generation = mock(GenerationGateway.class);
    private final StructuredModelService service = new StructuredModelService(
        generation,
        new ObjectMapper(),
        new GenerationProperties(0.1, 16)
    );

    /** 跨增量 JSON 正确合并，尾随解释与数组输出必须拒绝。 */
    @Test
    void shouldParseSplitJsonAndRejectTrailingTextOrNonObject() {
        when(generation.generate(any())).thenReturn(
            Flux.just(new GenerationDelta("{\"route\":", false), new GenerationDelta("\"SIMPLE\"}", true))
        );
        assertThat(call().path("route").asText()).isEqualTo("SIMPLE");
        when(generation.generate(any())).thenReturn(Flux.just(new GenerationDelta("{} 后续说明", true)));
        assertThatThrownBy(this::call).isInstanceOf(IllegalArgumentException.class);
        when(generation.generate(any())).thenReturn(Flux.just(new GenerationDelta("[]", true)));
        assertThatThrownBy(this::call).isInstanceOf(IllegalArgumentException.class);
    }

    /** 总时限超时取消模型资源，即使上游不停发送或没有完成也不能继续等待。 */
    @Test
    void shouldCancelUpstreamOnTotalTimeout() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(generation.generate(any())).thenReturn(
            Flux.<GenerationDelta>never().doOnCancel(() -> cancelled.set(true))
        );
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class);
        assertThat(cancelled).isTrue();
    }

    /** 超长输出在收集时立即终止，不等完整响应后才限制内存。 */
    @Test
    void shouldRejectOutputBeyondCharacterBudget() {
        when(generation.generate(any())).thenReturn(Flux.just(new GenerationDelta("x".repeat(3000), true)));
        assertThatThrownBy(this::call).isInstanceOf(IllegalArgumentException.class);
    }

    /** 字符保护预算读取配置，边界内 JSON 通过，跨增量超限立即拒绝。 */
    @Test
    void shouldApplyConfiguredCharacterBudgetAcrossDeltas() {
        StructuredModelService bounded = new StructuredModelService(
            generation,
            new ObjectMapper(),
            new GenerationProperties(0.1, 2)
        );
        when(generation.generate(any())).thenReturn(Flux.just(new GenerationDelta("{}", true)));
        assertThat(bounded.call("ROUTE", "严格 JSON", "问题", 1, Duration.ofSeconds(1)).isObject()).isTrue();
        when(generation.generate(any())).thenReturn(
            Flux.just(new GenerationDelta("{", false), new GenerationDelta("} ", true))
        );
        assertThatThrownBy(() -> bounded.call("ROUTE", "严格 JSON", "问题", 1, Duration.ofSeconds(1))).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    /** 使用短而固定的预算验证结构化模型契约。 */
    private com.fasterxml.jackson.databind.JsonNode call() {
        return service.call("ROUTE", "严格 JSON", "年假", 64, Duration.ofMillis(100));
    }
}
