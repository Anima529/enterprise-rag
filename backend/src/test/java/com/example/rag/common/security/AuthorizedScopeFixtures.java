package com.example.rag.common.security;

import java.util.List;

/** 仅用于测试的授权范围工厂；生产调用方通过 PermissionScopeService 获取范围。 */
public final class AuthorizedScopeFixtures {

    /** 限制工具或模型容器实例化，统一通过静态成员使用。 */
    private AuthorizedScopeFixtures() {
    }

    /** 为测试构造合法的不可公开创建授权范围。 */
    public static RetrievalScope issue(long user, long kb, long department) {
        return new RetrievalScope(user, List.of(new RetrievalScope.KnowledgeBaseDepartment(kb, department)));
    }
}
