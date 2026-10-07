package com.example.rag.rag.chat.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 部分回答落库间隔独立配置，最终消息仍始终保存。 */
@Validated
@ConfigurationProperties("rag.chat-persistence")
public record ChatPersistenceProperties(@NotNull Duration progressInterval) {
    /** 要求正间隔，防止每个流式分片都触发数据库写入。 */
    @AssertTrue
    public boolean isValid() {
        return progressInterval != null && progressInterval.isPositive();
    }
}
