package com.example.rag.governance.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 管理员日志查询使用明确的日期时区及有界时间窗口。 */
@Validated
@ConfigurationProperties("rag.admin-logs")
public record AdminLogProperties(@NotNull ZoneId timezone, @Min(1) int maxQueryDays) {}
