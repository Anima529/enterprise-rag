package com.example.rag.common.security;

import java.util.List;

/** 仅由 PermissionScopeService 签发的授权范围，不从 HTTP 请求体绑定。 */
public final class RetrievalScope {

    public record KnowledgeBaseDepartment(long knowledgeBaseId, long departmentId) {}

    private final long userId;
    private final List<KnowledgeBaseDepartment> knowledgeBases;

    /** 校验非空授权范围并复制知识库与部门配对，限制外部构造和修改。 */
    RetrievalScope(long userId, List<KnowledgeBaseDepartment> knowledgeBases) {
        if (userId <= 0 || knowledgeBases.isEmpty()) {
            throw new IllegalArgumentException("Nonempty authorized scope required");
        }
        this.userId = userId;
        this.knowledgeBases = List.copyOf(knowledgeBases);
    }

    /** 返回生成该授权检索范围的用户 ID。 */
    public long userId() {
        return userId;
    }

    /** 返回不可变的知识库与部门配对，防止调用方篡改范围。 */
    public List<KnowledgeBaseDepartment> knowledgeBases() {
        return knowledgeBases;
    }
}
