package com.example.rag.document.config.parser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.parser-persistence")
public record ParsedDocumentPersistenceProperties(@Min(1) @Max(1000) int batchSize) {}
