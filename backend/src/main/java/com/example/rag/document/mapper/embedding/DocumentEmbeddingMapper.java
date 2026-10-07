package com.example.rag.document.mapper.embedding;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentEmbeddingMapper {
    record Child(long id, String content) {}

    record VectorRow(long id, String vector, String contentHash) {}

    record Result(
        long documentId,
        int documentVersion,
        String model,
        int dimensions,
        Instant chunkedAt,
        int childCount,
        int batchCount,
        int callCount,
        long totalTokens,
        long durationMs,
        Instant embeddedAt
    ) {}

    /** 读取指定版本待生成向量的有效子 Chunk。 */
    List<Child> children(@Param("id") long id, @Param("version") int version);

    /** 批量写入子 Chunk 向量、模型和内容摘要。 */
    int write(
        @Param("id") long id,
        @Param("version") int version,
        @Param("rows") List<VectorRow> rows,
        @Param("model") String model,
        @Param("dimensions") int dimensions
    );

    /** 保存 Embedding 批次、调用、Token 和耗时汇总。 */
    void save(Result result);

    /** 读取仍与 Chunk 和解析快照一致的 Embedding 结果。 */
    Result result(@Param("id") long id, @Param("version") int version);

    /** 统计模型、维度、摘要及父 Chunk 均有效的向量子块。 */
    int validChildren(
        @Param("id") long id,
        @Param("version") int version,
        @Param("model") String model,
        @Param("dimensions") int dimensions
    );

    /** 查询文档版本当前索引状态。 */
    String status(@Param("id") long id, @Param("version") int version);
}
