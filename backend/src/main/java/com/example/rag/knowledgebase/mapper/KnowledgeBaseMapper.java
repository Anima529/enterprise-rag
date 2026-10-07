package com.example.rag.knowledgebase.mapper;

import com.example.rag.knowledgebase.domain.KnowledgeBaseStatus;
import com.example.rag.knowledgebase.dto.AclEntry;
import com.example.rag.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.rag.user.domain.UserAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeBaseMapper {
    // 列表、详情和检索范围复用同一权限条件；部门归属本身不授予访问权限。

    /** 按 ID 读取未删除的知识库。 */
    KnowledgeBaseResponse find(@Param("id") long id, @Param("actor") UserAccount actor);

    /** 按授权条件或管理条件分页查询知识库。 */
    List<KnowledgeBaseResponse> list(
        @Param("actor") UserAccount actor,
        @Param("management") boolean management,
        @Param("limit") int limit,
        @Param("offset") long offset
    );

    /** 按相同授权条件统计知识库数。 */
    long count(@Param("actor") UserAccount actor, @Param("management") boolean management);

    /** 锁定未删除知识库以串行处理管理和授权写入。 */
    Long lock(long id);

    /** 创建知识库并保存归属和模型配置。 */
    long insert(
        @Param("name") String name,
        @Param("description") String description,
        @Param("departmentId") long departmentId,
        @Param("ownerUserId") long ownerUserId,
        @Param("embeddingModel") String embeddingModel,
        @Param("embeddingDimensions") int embeddingDimensions,
        @Param("generationModel") String generationModel
    );

    /** 同步知识库模型配置，保留已有向量的模型标记。 */
    int synchronizeModels(
        @Param("embeddingModel") String embeddingModel,
        @Param("embeddingDimensions") int embeddingDimensions,
        @Param("generationModel") String generationModel
    );

    /** 更新知识库可编辑属性和状态。 */
    void update(
        @Param("id") long id,
        @Param("name") String name,
        @Param("description") String description,
        @Param("status") KnowledgeBaseStatus status
    );

    /** 软删除知识库并记录更新时间。 */
    void softDelete(long id);

    /** 查询知识库完整 ACL 集合。 */
    List<AclEntry> acl(long id);

    /** 删除知识库原有 ACL，供事务内完整替换。 */
    void deleteAcl(long id);

    /** 保存一条知识库主体授权。 */
    void insertAcl(@Param("id") long id, @Param("entry") AclEntry entry);

    /** 检查部门主体是否有效。 */
    boolean activeDepartment(long id);

    /** 检查用户主体是否有效。 */
    boolean activeUser(long id);
}
