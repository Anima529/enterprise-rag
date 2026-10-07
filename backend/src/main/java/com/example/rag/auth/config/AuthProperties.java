package com.example.rag.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.auth")
public record AuthProperties(@Min(10) @Max(16) int bcryptStrength) {}
