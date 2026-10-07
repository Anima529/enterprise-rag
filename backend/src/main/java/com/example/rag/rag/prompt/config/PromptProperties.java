package com.example.rag.rag.prompt.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/** 提示词位置统一配置，支持 classpath 资源或部署环境提供的 file 资源。 */
@Validated
@ConfigurationProperties("rag.prompts")
public record PromptProperties(
    @NotNull Resource answer,
    @NotNull Resource rewrite,
    @NotNull Resource router,
    @NotNull Resource decompose,
    @NotNull Resource hyde,
    @NotNull Resource faithfulness,
    @NotNull Resource relevance
) {
    /** 指定资源绑定使用完整规范构造器，兼容六参数测试构造器。 */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public PromptProperties {
    }

    /** 保留既有测试和配置构造契约，新 Judge 使用独立资源默认值。 */
    public PromptProperties(
        Resource answer,
        Resource rewrite,
        Resource router,
        Resource decompose,
        Resource hyde,
        Resource faithfulness
    ) {
        this(
            answer,
            rewrite,
            router,
            decompose,
            hyde,
            faithfulness,
            new org.springframework.core.io.ClassPathResource("prompts/relevance.st")
        );
    }
}
