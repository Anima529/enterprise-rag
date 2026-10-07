package com.example.rag.conversation.service;

import static com.example.rag.conversation.dto.ConversationModels.*;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.conversation.mapper.ConversationMapper;
import com.example.rag.rag.faithfulness.service.EvidenceJudge.Verification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private final ConversationMapper mapper;
    private final PermissionScopeService permissions;
    private final CitationPreviewBuilder previews;
    private final ObjectMapper json;

    /** 配置会话持久化、当前用户授权和引用恢复依赖。 */
    public ConversationService(
        ConversationMapper mapper,
        PermissionScopeService permissions,
        CitationPreviewBuilder previews,
        ObjectMapper json
    ) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.previews = previews;
        this.json = json;
    }

    /** 为当前用户创建会话，规范化输入标题。 */
    public Conversation create(String title) {
        long userId = permissions.currentUser().id();
        return mapper.create(userId, title.trim());
    }

    /** 确认会话存在且属于指定用户，否则拒绝访问。 */
    public Conversation require(long conversationId, long userId) {
        Conversation conversation = mapper.find(conversationId, userId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CHAT_404_NOT_FOUND);
        }
        return conversation;
    }

    /** 查询当前用户所属会话详情。 */
    public Conversation detail(long conversationId) {
        long userId = permissions.currentUser().id();
        return require(conversationId, userId);
    }

    /** 分页返回当前用户的会话及总数。 */
    public PageResponse<Conversation> list(PageRequest page) {
        long userId = permissions.currentUser().id();
        return new PageResponse<>(
            mapper.list(userId, page.pageSize(), page.offset()),
            mapper.count(userId),
            page.page(),
            page.pageSize()
        );
    }

    /** 校验会话归属后修改标题并查询更新结果。 */
    public Conversation rename(long conversationId, String title) {
        long userId = permissions.currentUser().id();
        require(conversationId, userId);

        mapper.rename(conversationId, userId, title.trim());
        return require(conversationId, userId);
    }

    /** 校验会话归属并在事务中软删除会话。 */
    @Transactional
    public void delete(long conversationId) {
        long userId = permissions.currentUser().id();
        if (mapper.lock(conversationId, userId) == null) {
            throw new BusinessException(ErrorCode.CHAT_404_NOT_FOUND);
        }
        mapper.expire(conversationId);
        if (mapper.busy(conversationId)) {
            throw new BusinessException(ErrorCode.CHAT_409_BUSY);
        }
        mapper.delete(conversationId, userId);
    }

    /** 校验会话归属后分页读取消息并恢复引用快照。 */
    public PageResponse<Message> messages(long conversationId, PageRequest page) {
        detail(conversationId);
        mapper.expire(conversationId);

        List<Message> messages = mapper
            .messages(conversationId, page.pageSize(), page.offset())
            .stream()
            .map(this::restoreMessage)
            .toList();

        return new PageResponse<>(messages, mapper.countMessages(conversationId), page.page(), page.pageSize());
    }

    /** 将消息行与其引用快照组合为历史消息响应。 */
    private Message restoreMessage(MessageRow row) {
        // 引用读取历史快照，使文档版本切换后仍能恢复当时的证据。
        List<Citation> citations = mapper.citations(row.id()).stream().map(previews::restore).toList();

        return new Message(
            row.id(),
            row.conversationId(),
            row.role(),
            row.content(),
            row.requestId(),
            row.traceId(),
            row.status(),
            row.errorCode(),
            row.confidenceLevel(),
            row.faithfulnessStatus(),
            row.inputTokens(),
            row.outputTokens(),
            row.createdAt(),
            citations,
            restoreVerification(row),
            row.estimatedCost()
        );
    }

    /** 旧消息没有核验详情时保留原有状态，新消息恢复最终覆盖率和断言。 */
    private Verification restoreVerification(MessageRow row) {
        if (row.verificationJson() == null || "{}".equals(row.verificationJson())) {
            return null;
        }
        try {
            return json.readValue(row.verificationJson(), Verification.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Invalid verification snapshot", failure);
        }
    }
}
