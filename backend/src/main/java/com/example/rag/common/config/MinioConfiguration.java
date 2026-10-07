package com.example.rag.common.config;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfiguration {

    /** 创建使用配置端点、凭据和超时的 MinIO 客户端。 */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(properties.timeout())
            .readTimeout(properties.timeout())
            .writeTimeout(properties.timeout())
            .callTimeout(properties.timeout())
            .build();
        return MinioClient.builder()
            .endpoint(properties.endpoint().toString())
            .credentials(properties.accessKey(), properties.secretKey())
            .httpClient(http)
            .build();
    }
}
