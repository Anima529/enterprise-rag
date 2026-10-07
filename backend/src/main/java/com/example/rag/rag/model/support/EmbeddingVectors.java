package com.example.rag.rag.model.support;

import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.rag.model.domain.ModelFailureCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public final class EmbeddingVectors {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private EmbeddingVectors() {
    }

    /** 检查向量维度、有限数值及非零范数。 */
    public static void validate(List<Float> vector, int dimensions) {
        if (
            vector == null ||
            vector.size() != dimensions ||
            vector.stream().anyMatch(v -> v == null || !Float.isFinite(v)) ||
            vector.stream().noneMatch(v -> v != 0)
        ) {
            throw new IndexFailure(
                ModelFailureCode.MODEL_422_DIMENSION_MISMATCH,
                "向量维度、数值或零向量不合法",
                false
            );
        }
    }

    /** 将已校验向量转换为 PGVector 输入字面量。 */
    public static String literal(List<Float> vector, int dimensions) {
        validate(vector, dimensions);
        return vector
            .stream()
            .map(String::valueOf)
            .collect(Collectors.joining(",", "[", "]"));
    }

    /** 计算向量来源文本摘要，用于验证内容一致性。 */
    public static String hash(String content) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
