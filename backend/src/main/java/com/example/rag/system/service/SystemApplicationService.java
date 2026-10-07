package com.example.rag.system.service;

import com.example.rag.common.config.MinioHealthIndicator;
import com.example.rag.system.domain.DatabaseInfo;
import com.example.rag.system.dto.SystemStatusResponse;
import com.example.rag.system.mapper.SystemMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.actuate.data.redis.RedisHealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

@Service
public class SystemApplicationService {

    private final SystemMapper mapper;
    private final RedisHealthIndicator redis;
    private final MinioHealthIndicator minio;

    /** 配置数据库、Redis 和私有存储的状态检查依赖。 */
    public SystemApplicationService(SystemMapper mapper, RedisConnectionFactory redis, MinioHealthIndicator minio) {
        this.mapper = mapper;
        this.redis = new RedisHealthIndicator(redis);
        this.minio = minio;
    }

    /** 组合服务、数据库、缓存及存储的状态摘要。 */
    public SystemStatusResponse status() {
        DatabaseInfo database = mapper.databaseInfo();
        return new SystemStatusResponse(
            "enterprise-rag",
            Instant.now(),
            database,
            Map.of(
                "postgres",
                "UP",
                "redis",
                redis.health().getStatus().getCode(),
                "minio",
                minio.health().getStatus().getCode()
            )
        );
    }
}
