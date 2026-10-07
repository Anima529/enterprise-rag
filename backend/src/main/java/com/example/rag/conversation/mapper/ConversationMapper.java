package com.example.rag.conversation.mapper;

import static com.example.rag.conversation.dto.ConversationModels.*;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper {
    record RetrievalTrace(String scopeJson, String traceJson) {}

    /** 只读取本人未删除会话的追踪；历史候选返回前还须复查知识库权限。 */
    RetrievalTrace trace(@Param("request") String requestId, @Param("user") long userId);

    record History(String role, String content) {}

    /** 读取本人已完成且范围未扩大的最近消息，排除当前请求和失败或取消轮次。 */
    List<History> history(
        @Param("conversation") long conversationId,
        @Param("user") long userId,
        @Param("request") String requestId,
        @Param("scope") String scopeJson,
        @Param("limit") int limit
    );

    /** 在消息终态事务内保存核验结果和检索追踪，历史刷新与 SSE 一致。 */
    void verification(
        @Param("id") long messageId,
        @Param("confidence") String confidence,
        @Param("status") String status,
        @Param("details") String details,
        @Param("route") String route,
        @Param("trace") String trace
    );

    /** 按会话和用户 ID 查询未删除的会话。 */
    Conversation find(@Param("id") long id, @Param("user") long userId);

    /** 锁定用户所属会话，串行处理会话内的写入。 */
    Conversation lock(@Param("id") long id, @Param("user") long userId);

    /** 插入用户会话并返回创建结果。 */
    Conversation create(@Param("user") long userId, @Param("title") String title);

    /** 按更新时间分页查询用户的有效会话。 */
    List<Conversation> list(@Param("user") long userId, @Param("limit") int limit, @Param("offset") long offset);

    /** 统计用户未删除的会话数。 */
    long count(long userId);

    /** 更新用户所属会话的标题与更新时间。 */
    int rename(@Param("id") long id, @Param("user") long userId, @Param("title") String title);

    /** 软删除用户所属会话。 */
    int delete(@Param("id") long id, @Param("user") long userId);

    /** 将已过期的运行中消息与请求追踪一并终结，恢复服务重启前的孤立请求。 */
    void expire(long id);

    /** 检查会话是否仍有未过期的生成任务。 */
    boolean busy(long id);

    /** 保存请求消息及追踪、状态和授权知识库信息。 */
    long insert(
        @Param("conversation") long conversationId,
        @Param("user") long userId,
        @Param("role") String role,
        @Param("content") String content,
        @Param("request") String requestId,
        @Param("trace") String traceId,
        @Param("status") String status,
        @Param("scope") String scopeJson,
        @Param("timeoutMs") long timeoutMs
    );

    /** 更新会话活跃时间以调整侧栏排序。 */
    void touch(long id);

    /** 仅将运行中消息更新为终态并保存内容与 Token 用量。 */
    int finish(
        @Param("id") long id,
        @Param("content") String content,
        @Param("status") String status,
        @Param("error") String errorCode,
        @Param("input") Long inputTokens,
        @Param("output") Long outputTokens
    );

    /** 保存运行中消息的最新部分回答。 */
    int progress(@Param("id") long id, @Param("content") String content);

    /** 按消息顺序分页读取会话历史。 */
    List<MessageRow> messages(@Param("id") long id, @Param("limit") int limit, @Param("offset") long offset);

    /** 统计会话中的消息数。 */
    long countMessages(long id);

    /** 查询请求所属用户，供取消请求校验身份。 */
    Long requestOwner(String requestId);

    /** 将没有本机工作线程的请求及追踪一并取消，避免遗留 RUNNING 统计。 */
    void cancelRequest(String requestId);

    /** 保存消息引用的来源位置、表格及混合预览快照。 */
    void citation(
        @Param("message") long messageId,
        @Param("c") Citation citation,
        @Param("tables") String tables,
        @Param("preview") String preview
    );

    /** 读取引用快照，仅为缺少快照的旧引用关联匹配的来源元数据。 */
    List<CitationSnapshot> citations(long id);

    /** 将实际入模正文与来源位置写入同一引用事务，不能事后读取当前版本替代。 */
    void citationEvidence(
        @Param("message") long messageId,
        @Param("citation") String citationId,
        @Param("content") String content,
        @Param("metadata") String metadataJson
    );

    record SourceRow(String content, String metadataJson, com.example.rag.document.domain.DocumentFormat format) {}

    /** 限定会话归属和消息引用，旧快照仅返回其保存的片段，不关联后续版本正文。 */
    SourceRow source(
        @Param("conversation") long conversationId,
        @Param("message") long messageId,
        @Param("citation") String citationId,
        @Param("user") long userId
    );
}
