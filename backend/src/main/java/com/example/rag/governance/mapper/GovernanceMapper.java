package com.example.rag.governance.mapper;

import com.example.rag.governance.dto.AdminLogModels.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 治理所需的实时权限快照、缓存来源检查和脱敏审计查询。 */
@Mapper
public interface GovernanceMapper {
    /** 按管理员筛选条件分页查询审计元数据，时间与 ID 倒序。 */
    List<AuditLog> auditLogs(
        @Param("filter") LogFilter filter,
        @Param("limit") int limit,
        @Param("offset") long offset
    );

    /** 使用与列表相同的条件统计审计记录。 */
    long countAuditLogs(@Param("filter") LogFilter filter);

    /** 查询请求状态及脱敏错误码，不读取消息内容或检索快照。 */
    List<RequestLog> requestLogs(
        @Param("filter") LogFilter filter,
        @Param("limit") int limit,
        @Param("offset") long offset
    );

    /** 使用与列表相同的条件统计请求追踪。 */
    long countRequestLogs(@Param("filter") LogFilter filter);

    /** 读取数据库/schema 命名空间以隔离不同环境的 Redis Key。 */
    String namespace();

    /** 返回用户身份状态的稳定 JSON 文本，不含密码字段。 */
    String userSnapshot(@Param("userId") long userId);

    /** 返回知识库归属及启停状态的稳定 JSON 文本。 */
    String knowledgeBaseSnapshot(@Param("knowledgeBaseId") long knowledgeBaseId);

    /** 按 ACL ID 排序聚合当前授权，空集合返回 JSON 空数组。 */
    String aclSnapshot(@Param("knowledgeBaseId") long knowledgeBaseId);

    /** 按文档 ID 排序聚合版本、删除与状态，用于缓存失效。 */
    String documentSnapshot(@Param("knowledgeBaseId") long knowledgeBaseId);

    /** 统计会话已完成助手消息，避免单轮缓存复用于多轮。 */
    int completedAnswers(@Param("conversationId") long conversationId);

    /** 复查引用 Chunk 与文档当前有效版本，返回其真实知识库。 */
    List<Long> citationKnowledgeBases(
        @Param("documentId") long documentId,
        @Param("version") int version,
        @Param("chunkId") long chunkId
    );

    /** 保存敏感操作身份与追踪，不保存请求正文或认证凭据。 */
    int audit(
        @Param("actor") long actor,
        @Param("action") String action,
        @Param("resourceId") Long resourceId,
        @Param("requestId") String requestId,
        @Param("traceId") String traceId
    );
}
