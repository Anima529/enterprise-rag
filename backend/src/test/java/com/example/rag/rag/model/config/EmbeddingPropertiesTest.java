package com.example.rag.rag.model.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmbeddingPropertiesTest {

    /** 验证模型可配置且存储不兼容维度被拒绝。 */
    @Test
    void shouldAcceptConfiguredModelsAndRejectDimensionsIncompatibleWithStorage() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            for (String model : new String[] {
                "qwen3.7-text-embedding-flash",
                "text-embedding-v3",
                "custom-embedding-model",
            }) {
                assertThat(factory.getValidator().validate(config(model, 1024))).isEmpty();
            }
            assertThat(factory.getValidator().validate(config("qwen3.7-text-embedding-flash", 512))).anySatisfy(
                violation -> assertThat(violation.getMessage()).contains("vector(1024)")
            );
            assertThat(factory.getValidator().validate(config(" ", 1024))).isNotEmpty();
        }
    }

    /** 构造用例所需的模型或切分配置。 */
    private EmbeddingProperties config(String model, int dimensions) {
        return new EmbeddingProperties(
            model,
            dimensions,
            "https://example.invalid/v1",
            "",
            "",
            10,
            2,
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            2,
            Duration.ofMillis(500),
            100,
            100
        );
    }
}
