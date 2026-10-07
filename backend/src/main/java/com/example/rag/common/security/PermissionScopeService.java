package com.example.rag.common.security;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.common.api.ApiContractLimits;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.knowledgebase.domain.KnowledgeBaseStatus;
import com.example.rag.knowledgebase.domain.Permission;
import com.example.rag.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.service.UserApplicationService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PermissionScopeService {

    private final UserApplicationService users;
    private final KnowledgeBaseMapper mapper;

    /** 配置实时账号状态和知识库授权查询依赖。 */
    public PermissionScopeService(UserApplicationService users, KnowledgeBaseMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    /** 根据当前登录主体重新查询有效账号。 */
    public UserAccount currentUser() {
        return users.requireActive(StpUtil.getLoginIdAsLong());
    }

    /** 限制管理入口仅允许系统管理员访问。 */
    public void requireSystemAdmin(UserAccount actor) {
        if (!AccountProtocol.SYSTEM_ADMIN.equals(actor.role())) {
            throw new BusinessException(ErrorCode.AUTH_403_FORBIDDEN);
        }
    }

    /** 重新校验知识库状态与所需权限，管理入口另行限制管理员身份。 */
    public KnowledgeBaseResponse require(long id, UserAccount actor, Permission required, boolean management) {
        if (management) {
            requireSystemAdmin(actor);
        }
        KnowledgeBaseResponse kb = mapper.find(id, actor);
        if (kb == null) {
            throw new BusinessException(ErrorCode.KB_404_NOT_FOUND);
        }
        if (kb.permission() == null || !kb.permission().includes(required)) {
            throw new BusinessException(ErrorCode.KB_403_FORBIDDEN);
        }
        if (!management && kb.status() != KnowledgeBaseStatus.ENABLED) {
            throw new BusinessException(ErrorCode.KB_409_DISABLED);
        }
        return kb;
    }

    /** 每次请求（包括重试）都重新计算范围；任一知识库无权访问时拒绝整个请求。 */
    public RetrievalScope resolve(Collection<Long> requestedIds) {
        UserAccount actor = currentUser();
        return resolveFor(actor.id(), requestedIds);
    }

    /** 为后台评估签发指定活跃身份的实时范围，仍逐个检查 ACL，不能由客户端直接构造范围。 */
    public RetrievalScope resolveFor(long userId, Collection<Long> requestedIds) {
        UserAccount actor = users.requireActive(userId);
        if (requestedIds == null || requestedIds.isEmpty()) {
            throw new BusinessException(ErrorCode.RAG_403_EMPTY_SCOPE);
        }
        if (requestedIds.stream().anyMatch(id -> id == null || id <= 0 || id > ApiContractLimits.MAX_SAFE_ID)) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        List<RetrievalScope.KnowledgeBaseDepartment> pairs = new LinkedHashSet<>(requestedIds)
            .stream()
            .map(id -> {
                KnowledgeBaseResponse kb = require(id, actor, Permission.READ, false);
                return new RetrievalScope.KnowledgeBaseDepartment(kb.id(), kb.departmentId());
            })
            .toList();
        return new RetrievalScope(actor.id(), pairs);
    }
}
