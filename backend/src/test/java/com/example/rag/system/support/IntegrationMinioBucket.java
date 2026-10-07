package com.example.rag.system.support;

import io.minio.*;
import io.minio.messages.Item;
import java.time.Duration;
import okhttp3.OkHttpClient;

/** 验收脚本专用的私有临时桶生命周期，不允许接收开发或生产桶名称。 */
public final class IntegrationMinioBucket {

    private static final String BUCKET_PATTERN = "rag-integration-[0-9a-f]{32}";
    private static final String CREATE = "create";
    private static final String CLEAN = "clean";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    /** 生命周期工具仅由验收脚本以静态入口运行。 */
    private IntegrationMinioBucket() {
    }

    /** 先验证操作与桶名，再读取现有基础设施凭据执行创建或清理。 */
    public static void main(String[] arguments) throws Exception {
        if (
            arguments.length != 2 ||
            !arguments[1].matches(BUCKET_PATTERN) ||
            (!CREATE.equals(arguments[0]) && !CLEAN.equals(arguments[0]))
        ) {
            throw new IllegalArgumentException("Only a UUID-named integration bucket may be managed");
        }
        OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT)
            .readTimeout(REQUEST_TIMEOUT)
            .writeTimeout(REQUEST_TIMEOUT)
            .build();
        MinioClient minio = MinioClient.builder()
            .endpoint(System.getenv("MINIO_ENDPOINT"))
            .credentials(System.getenv("MINIO_ACCESS_KEY"), System.getenv("MINIO_SECRET_KEY"))
            .httpClient(http)
            .build();
        String bucket = arguments[1];
        try {
            if (CREATE.equals(arguments[0])) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                System.out.println("本次私有 MinIO 测试桶已创建：" + bucket);
            } else {
                clean(minio, bucket);
                System.out.println("本次私有 MinIO 测试桶已清理：" + bucket);
            }
        } finally {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
    }

    /** 仅清空本次随机命名的测试桶，桶不存在时保持清理幂等。 */
    private static void clean(MinioClient minio, String bucket) throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            return;
        }
        Iterable<Result<Item>> objects = minio.listObjects(
            ListObjectsArgs.builder().bucket(bucket).recursive(true).build()
        );
        for (Result<Item> entry : objects) {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(entry.get().objectName()).build());
        }
        minio.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
    }
}
