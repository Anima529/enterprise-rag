package com.example.rag.document.mapper.chunk;

import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.rag.retrieve.support.KeywordAnalyzer;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentChunkMapper {
    record Scope(long knowledgeBaseId, long departmentId) {}

    record Key(long id, ChunkType chunkType, int chunkIndex) {}

    record Summary(
        String strategyVersion,
        String tokenizer,
        Instant chunkedAt,
        int childTokens,
        int parentTokens,
        int overlapTokens,
        Integer boundaryLookbackCharacters,
        int parentCount,
        int childCount
    ) {}

    record Row(
        long id,
        long documentId,
        int documentVersion,
        long knowledgeBaseId,
        long departmentId,
        Long parentChunkId,
        ChunkType chunkType,
        int chunkIndex,
        String content,
        int tokenCount,
        Integer pageNumber,
        String chapter,
        String section,
        String metadataJson
    ) {
        /** Child 与正文原子保存词项，Parent 仅用于生成且不建关键词索引。 */
        public String keywordText() {
            return chunkType == ChunkType.CHILD ? KeywordAnalyzer.text(content) : "";
        }

        /** 保存分析器版本，便于历史补齐和后续词项策略升级。 */
        public String keywordAnalyzer() {
            return chunkType == ChunkType.CHILD ? KeywordAnalyzer.VERSION : "";
        }
    }

    /** 读取有效文档版本所属的知识库与部门配对。 */
    Scope scope(@Param("id") long id, @Param("version") int version);

    /** 读取现有 Chunk 的稳定 ID 与类型序号。 */
    List<Key> keys(@Param("id") long id, @Param("version") int version);

    /** 批量预分配新 Chunk ID。 */
    List<Long> allocateIds(int count);

    /** 保存本次切分的配置、版本和父子数量汇总。 */
    int upsertSummary(
        @Param("id") long id,
        @Param("version") int version,
        @Param("strategy") String strategy,
        @Param("tokenizer") String tokenizer,
        @Param("config") ChunkProperties config,
        @Param("parents") int parents,
        @Param("children") int children
    );

    /** 将版本原有 Chunk 标记为不可见，供重建替换。 */
    int hide(@Param("id") long id, @Param("version") int version);

    /** 删除该版本旧 Embedding 汇总，防止新 Chunk 复用旧向量状态。 */
    void invalidateEmbeddings(@Param("id") long id, @Param("version") int version);

    /** 批量保存父子 Chunk，复用已有稳定 ID 并恢复有效状态。 */
    int upsertRows(@Param("rows") List<Row> rows);

    /** 查询与当前解析结果仍一致的 Chunk 汇总。 */
    Summary summary(@Param("id") long id, @Param("version") int version);

    /** 按版本和可选类型读取有效 Chunk 的来源及内容。 */
    List<Row> rows(
        @Param("id") long id,
        @Param("version") int version,
        @Param("type") ChunkType type,
        @Param("limit") int limit,
        @Param("offset") long offset
    );
}
