package com.example.rag.rag.model.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 生成采样与结构化输出的字符保护预算，不代替实际 Token 计量。 */
@Validated
@ConfigurationProperties("rag.generation")
public record GenerationProperties(
    @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
    @Min(1) int structuredOutputCharactersPerToken
) {}
