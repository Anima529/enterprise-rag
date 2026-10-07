package com.example.rag.common.config;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("minioHealthIndicator")
public class MinioHealthIndicator implements HealthIndicator {

    private static final String BUCKET_MISSING = "BUCKET_MISSING";
    private static final String PRIVATE_BUCKET_REQUIRED = "BUCKET_POLICY_MUST_BE_PRIVATE";
    private static final String MINIO_UNAVAILABLE = "MINIO_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(MinioHealthIndicator.class);
    private final MinioClient client;
    private final MinioProperties properties;

    /** 配置私有桶健康检查所需的客户端和桶信息。 */
    public MinioHealthIndicator(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 检查目标桶是否存在且为私有桶，失败时隐藏供应方细节。 */
    @Override
    public Health health() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) {
                return Health.down().withDetail("reason", BUCKET_MISSING).build();
            }
            try {
                String policy = client.getBucketPolicy(
                    GetBucketPolicyArgs.builder().bucket(properties.bucket()).build()
                );
                if (!policy.isBlank()) {
                    return Health.down().withDetail("reason", PRIVATE_BUCKET_REQUIRED).build();
                }
            } catch (ErrorResponseException exception) {
                if (!"NoSuchBucketPolicy".equals(exception.errorResponse().code())) {
                    throw exception;
                }
            }
            return Health.up().build();
        } catch (Exception exception) {
            log.warn("MinIO health check failed, exceptionType={}", exception.getClass().getSimpleName());
            return Health.down().withDetail("reason", MINIO_UNAVAILABLE).build();
        }
    }
}
