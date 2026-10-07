package com.example.rag.document.mapper;

import com.example.rag.document.domain.DocumentFormat;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentVersionMapper {
    /** 查询文档归属的知识库 ID。 */
    Long knowledgeBase(long id);

    /** 锁定文档所属知识库以串行校验版本写入。 */
    Long lockKnowledgeBase(long id);

    record Version(
        int versionNo,
        String name,
        DocumentFormat sourceFormat,
        String sha256,
        long sizeBytes,
        String status,
        Instant createdAt,
        Instant updatedAt
    ) {}

    record Cleanup(
        long id,
        long documentId,
        int documentVersion,
        String cleanupType,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        String errorCode
    ) {}

    /** 锁定有效文档，串行分配新版本。 */
    Long lock(long id);

    /** 基于全部已有版本分配下一个版本号，避免复用失败版本。 */
    int nextVersion(long id);

    /** 检查文档是否已有待执行或运行中的版本构建。 */
    boolean building(long id);

    /** 插入新上传文件对应的版本元数据。 */
    void insert(
        @Param("id") long id,
        @Param("version") int version,
        @Param("file") com.example.rag.document.domain.upload.ValidatedUpload file,
        @Param("mime") String mime,
        @Param("size") long size,
        @Param("key") String key
    );

    /** 复制指定版本的原文件元数据，创建重新索引的版本。 */
    void copy(@Param("id") long id, @Param("version") int version, @Param("current") int current);

    /** 创建指定版本及类型的索引任务。 */
    void task(@Param("id") long id, @Param("version") int version, @Param("type") String type);

    /** 分页读取文档版本列表。 */
    List<Version> versions(@Param("id") long id, @Param("limit") int limit, @Param("offset") long offset);

    /** 统计文档全部版本数。 */
    long count(long id);

    /** 查询文档的清理任务及失败原因。 */
    List<Cleanup> cleanup(long id);

    /** 领取到期清理任务并延后下次领取时间。 */
    Cleanup claim(long leaseMs);

    /** 查询清理范围内去重后的原文件对象键。 */
    List<String> keys(long id);

    /** 检查对象是否仍被有效文档版本引用。 */
    boolean activeReference(String key);

    /** 删除指定文档版本范围的 Chunk 结果。 */
    void removeChunks(@Param("id") long id, @Param("version") int version);

    /** 删除指定文档版本范围的解析结果。 */
    void removeParsed(@Param("id") long id, @Param("version") int version);

    /** 保存清理任务状态及下次尝试时间。 */
    int finish(
        @Param("task") Cleanup task,
        @Param("status") String status,
        @Param("error") String error,
        @Param("due") Instant due
    );
}
