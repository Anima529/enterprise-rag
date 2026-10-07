package com.example.rag.document.mapper;

import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.upload.ValidatedUpload;
import com.example.rag.document.dto.DocumentResponse;
import com.example.rag.document.dto.DocumentTaskResponse;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentMapper {
    /** 查询指定文档版本的真实格式，原文预览不得使用当前版本格式代替。 */
    DocumentFormat versionFormat(@Param("id") long id, @Param("version") int version);

    /** 查询未删除文档及当前版本的公开元数据。 */
    DocumentResponse find(long id);

    /** 分页查询知识库中未删除的文档。 */
    List<DocumentResponse> list(@Param("kbId") long kbId, @Param("limit") int limit, @Param("offset") long offset);

    /** 统计知识库中未删除的文档数。 */
    long count(long kbId);

    /** 判断同一知识库是否已有相同摘要的有效文档。 */
    boolean duplicate(@Param("kbId") long kbId, @Param("sha") String sha);

    /** 预分配文档 ID，供原文件对象命名。 */
    long nextId();

    /** 保存首次上传的文档元数据。 */
    void insert(
        @Param("id") long id,
        @Param("kbId") long kbId,
        @Param("file") ValidatedUpload file,
        @Param("mime") String mime,
        @Param("size") long size,
        @Param("key") String key,
        @Param("actorId") long actorId
    );

    /** 从文档元数据创建第一个版本。 */
    void insertVersion(long id);

    /** 为文档版本创建待执行索引任务。 */
    void insertTask(long id);

    /** 分页查询文档索引任务及重试状态。 */
    List<DocumentTaskResponse> tasks(@Param("id") long id, @Param("limit") int limit, @Param("offset") long offset);

    /** 统计文档的索引任务数。 */
    long countTasks(long id);

    /** 查询指定版本的原文件对象键。 */
    String objectKey(@Param("id") long id, @Param("version") int version);

    /** 查询指定版本的原文件名称。 */
    String versionName(@Param("id") long id, @Param("version") int version);

    /** 软删除文档并记录更新时间。 */
    void softDelete(long id);

    /** 安排指定版本的持久化清理任务。 */
    void scheduleDeletion(@Param("id") long id, @Param("due") Instant due);

    /** 在对象上传前登记补偿记录，保证异常后仍可清理。 */
    void registerUpload(@Param("key") String key, @Param("due") Instant due);

    /** 锁定上传补偿记录，防止与清理任务同时提交。 */
    String lockUpload(@Param("key") String key, @Param("due") Instant due);

    /** 删除已被文档版本正式接管的上传补偿记录。 */
    void clearUpload(String key);

    /** 更新到期对象清理的领取时间并返回对象键。 */
    List<String> claimCleanup(@Param("limit") int limit, @Param("leaseUntil") Instant leaseUntil);

    /** 检查原文件是否已被任一文档版本引用。 */
    boolean objectReferenced(String key);
}
