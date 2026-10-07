package com.example.rag.document.mapper.parser;

import com.example.rag.document.domain.DocumentFormat;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ParsedDocumentMapper {
    record Source(DocumentFormat format, String objectKey, int sizeBytes, String sha256) {}

    record Summary(String parserVersion, Instant parsedAt, int characterCount, String warningsJson, long blockCount) {}

    record BlockRow(int blockIndex, String text, Integer page, String chapter, String section, String metadataJson) {}

    record ResultWrite(
        long documentId,
        int documentVersion,
        DocumentFormat format,
        String parserVersion,
        int blockCount,
        int characterCount,
        String warningsJson
    ) {}

    /** 查询有效文档版本的原文件来源。 */
    Source source(@Param("id") long id, @Param("version") int version);

    /** 查询版本解析格式、统计和告警汇总。 */
    Summary summary(@Param("id") long id, @Param("version") int version);

    /** 按来源顺序分页读取版本解析块。 */
    List<BlockRow> blocks(
        @Param("id") long id,
        @Param("version") int version,
        @Param("limit") int limit,
        @Param("offset") long offset
    );

    /** 保存或替换版本解析汇总。 */
    int upsertResult(@Param("result") ResultWrite result);

    /** 删除同版本旧块，供事务内替换完整产物。 */
    int deleteBlocks(@Param("id") long id, @Param("version") int version);

    /** 批量保存解析块和来源元数据。 */
    int insertBlocks(@Param("id") long id, @Param("version") int version, @Param("blocks") List<BlockRow> blocks);
}
