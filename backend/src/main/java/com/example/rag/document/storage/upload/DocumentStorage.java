package com.example.rag.document.storage.upload;

import com.example.rag.common.config.MinioProperties;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.upload.ValidatedUpload;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;

@Component
public class DocumentStorage {

    private final MinioClient client;
    private final MinioClient downloadClient;
    private static final String DEFAULT_MINIO_REGION = "us-east-1";
    private final String bucket;

    /** 按存储配置创建有界超时客户端并绑定私有桶。 */
    public DocumentStorage(MinioProperties minio, DocumentProperties properties) {
        bucket = minio.bucket();
        OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(minio.timeout())
            .readTimeout(properties.storageTimeout())
            .writeTimeout(properties.storageTimeout())
            .callTimeout(properties.storageTimeout())
            .build();
        client = MinioClient.builder()
            .endpoint(minio.endpoint().toString())
            .credentials(minio.accessKey(), minio.secretKey())
            .httpClient(http)
            .build();
        downloadClient = minio.endpoint().equals(minio.publicEndpoint())
            ? client
            : MinioClient.builder()
                  .endpoint(minio.publicEndpoint().toString())
                  .credentials(minio.accessKey(), minio.secretKey())
                  .region(DEFAULT_MINIO_REGION)
                  .httpClient(http)
                  .build();
    }

    /** 将校验后的原文件上传到私有桶，使用标准 MIME。 */
    public void put(String key, ValidatedUpload file) {
        try {
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(file.content()), file.content().length, -1)
                    .contentType(file.format().mime())
                    .build()
            );
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
        }
    }

    /** 删除指定原文件对象，供持久化清理或失败补偿使用。 */
    public void remove(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
        }
    }

    /** 打开私有原文件流，供索引阶段解析。 */
    public byte[] read(String key, int maxBytes) {
        try (GetObjectResponse input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] content = input.readNBytes(maxBytes + 1);
            if (content.length > maxBytes) {
                throw new BusinessException(ErrorCode.DOC_413_TOO_LARGE);
            }
            return content;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
        }
    }

    /** 为指定对象生成受配置时限约束的下载地址。 */
    public String download(String key, String name, int ttlSeconds) {
        return signedUrl(key, name, ttlSeconds, false);
    }

    /** 仅供已鉴权的 PDF 原文定位使用，签发 inline PDF 地址以支持浏览器跳页。 */
    public String previewPdf(String key, String name, int ttlSeconds) {
        return signedUrl(key, name, ttlSeconds, true);
    }

    /** 按调用方明确选择的下载或 PDF 预览方式签名，不允许文本或 HTML 原件 inline 执行。 */
    private String signedUrl(String key, String name, int ttlSeconds, boolean inlinePdf) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            return downloadClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry(ttlSeconds)
                    .extraQueryParams(
                        Map.of(
                            "response-content-disposition",
                            (inlinePdf ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded,
                            "response-content-type",
                            inlinePdf ? "application/pdf" : "application/octet-stream"
                        )
                    )
                    .build()
            );
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
        }
    }
}
