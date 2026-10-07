package com.example.rag.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class MinioHealthIndicatorTest {

    private final MinioClient client = mock(MinioClient.class);
    private final MinioProperties properties = new MinioProperties(
        URI.create("http://localhost:9000"),
        "test-only",
        "test-only",
        "rag-documents",
        Duration.ofSeconds(3)
    );
    private final MinioHealthIndicator health = new MinioHealthIndicator(client, properties);

    /** 验证存储健康要求目标桶存在且为私有桶。 */
    @Test
    void shouldRequireAnExistingPrivateBucket() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        assertThat(health.health().getDetails()).containsEntry("reason", "BUCKET_MISSING");
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(client.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("{policy}");
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("reason", "BUCKET_POLICY_MUST_BE_PRIVATE");
        when(client.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("");
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        when(client.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenThrow(
            new ErrorResponseException(
                new ErrorResponse("NoSuchBucketPolicy", "test", "rag-documents", null, null, null, null),
                null,
                "test"
            )
        );
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    /** 验证存储不可用响应不泄露供应方细节。 */
    @Test
    void shouldReportUnavailableWithoutPublishingProviderDetails() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(new IllegalStateException("secret=test"));
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsOnlyKeys("reason");
    }
}
