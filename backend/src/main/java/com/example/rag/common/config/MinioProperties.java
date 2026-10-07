package com.example.rag.common.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.minio")
public record MinioProperties(
    @NotNull URI endpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucket,
    @NotNull Duration timeout,
    @NotNull URI publicEndpoint
) {
    /** 完整配置构造器用于 Spring 绑定，下载端点默认由 YAML 沿用内部端点。 */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public MinioProperties {
    }

    /** 保留既有独立测试构造契约，未指定公开端点时继续使用当前端点。 */
    public MinioProperties(URI endpoint, String accessKey, String secretKey, String bucket, Duration timeout) {
        this(endpoint, accessKey, secretKey, bucket, timeout, endpoint);
    }

    /** 限制 MinIO 端点为无凭据、无额外路径的 HTTP(S) 地址。 */
    @AssertTrue(message = "MinIO endpoint must use HTTP(S), without credentials or path")
    public boolean isValidEndpoint() {
        return validEndpoint(endpoint) && validEndpoint(publicEndpoint);
    }

    /** 签名端点和内部端点均不允许嵌入凭据、路径或查询参数。 */
    private boolean validEndpoint(URI endpoint) {
        return (
            endpoint != null &&
            ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) &&
            endpoint.getHost() != null &&
            endpoint.getUserInfo() == null &&
            endpoint.getQuery() == null &&
            endpoint.getFragment() == null &&
            (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))
        );
    }

    /** 输出脱敏存储配置，避免访问密钥进入日志。 */
    @Override
    public String toString() {
        return "MinioProperties[bucket=" + bucket + ", timeout=" + timeout + "]";
    }

    /** 检查存储请求超时是否为正值。 */
    @AssertTrue(message = "MinIO timeout must be positive")
    public boolean isValidTimeout() {
        return timeout != null && !timeout.isNegative() && !timeout.isZero();
    }
}
