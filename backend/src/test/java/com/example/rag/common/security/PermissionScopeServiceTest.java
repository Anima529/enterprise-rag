package com.example.rag.common.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.knowledgebase.domain.KnowledgeBaseStatus;
import com.example.rag.knowledgebase.domain.Permission;
import com.example.rag.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.service.UserApplicationService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PermissionScopeServiceTest {

    private final UserApplicationService users = mock(UserApplicationService.class);
    private final KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
    private final PermissionScopeService service = new PermissionScopeService(users, mapper);
    private final UserAccount actor = new UserAccount(
        101L,
        "alpha.user",
        "test-only",
        10L,
        "A",
        "USER",
        "ACTIVE",
        false,
        "ACTIVE",
        false
    );

    /** 验证知识库与部门配对保持正确，授权范围不可公开构造或修改。 */
    @Test
    void shouldPreserveKnowledgeBaseDepartmentPairsAndIssueImmutableNonPublicScope() {
        try (MockedStatic<StpUtil> sessions = mockStatic(StpUtil.class)) {
            sessions.when(StpUtil::getLoginIdAsLong).thenReturn(101L);
            when(users.requireActive(101)).thenReturn(actor);
            when(mapper.find(200, actor)).thenReturn(kb(200, 20, Permission.READ, KnowledgeBaseStatus.ENABLED));
            when(mapper.find(100, actor)).thenReturn(kb(100, 10, Permission.ADMIN, KnowledgeBaseStatus.ENABLED));
            RetrievalScope scope = service.resolve(List.of(200L, 100L, 200L));
            assertThat(scope.userId()).isEqualTo(101);
            assertThat(scope.knowledgeBases()).containsExactly(
                new RetrievalScope.KnowledgeBaseDepartment(200, 20),
                new RetrievalScope.KnowledgeBaseDepartment(100, 10)
            );
            assertThatThrownBy(() -> scope.knowledgeBases().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(RetrievalScope.class.getConstructors()).isEmpty();
            verify(mapper, times(1)).find(200, actor);
        }
    }

    /** 验证混合未授权、空、无效、停用和删除来源均被拒绝。 */
    @Test
    void shouldRejectMixedUnauthorizedEmptyInvalidDisabledAndDeletedScopes() {
        try (MockedStatic<StpUtil> sessions = mockStatic(StpUtil.class)) {
            sessions.when(StpUtil::getLoginIdAsLong).thenReturn(101L);
            when(users.requireActive(101)).thenReturn(actor);
            when(mapper.find(100, actor)).thenReturn(kb(100, 10, Permission.READ, KnowledgeBaseStatus.ENABLED));
            when(mapper.find(200, actor)).thenReturn(kb(200, 20, null, KnowledgeBaseStatus.ENABLED));
            error(() -> service.resolve(List.of()), ErrorCode.RAG_403_EMPTY_SCOPE);
            error(() -> service.resolve(null), ErrorCode.RAG_403_EMPTY_SCOPE);
            error(() -> service.resolve(List.of(0L)), ErrorCode.COMMON_400_VALIDATION);
            error(() -> service.resolve(Arrays.asList(100L, null)), ErrorCode.COMMON_400_VALIDATION);
            error(() -> service.resolve(List.of(100L, 200L)), ErrorCode.KB_403_FORBIDDEN);
            when(mapper.find(100, actor)).thenReturn(kb(100, 10, Permission.READ, KnowledgeBaseStatus.DISABLED));
            error(() -> service.resolve(List.of(100L)), ErrorCode.KB_409_DISABLED);
            error(() -> service.resolve(List.of(999L)), ErrorCode.KB_404_NOT_FOUND);
        }
    }

    /** 验证权限撤销后重新计算范围而不复用旧授权。 */
    @Test
    void shouldRecomputeRevokedPermissionsRatherThanReusePriorScope() {
        try (MockedStatic<StpUtil> sessions = mockStatic(StpUtil.class)) {
            sessions.when(StpUtil::getLoginIdAsLong).thenReturn(101L);
            when(users.requireActive(101)).thenReturn(actor);
            when(mapper.find(200, actor)).thenReturn(
                kb(200, 20, Permission.READ, KnowledgeBaseStatus.ENABLED),
                kb(200, 20, null, KnowledgeBaseStatus.ENABLED)
            );
            assertThat(service.resolve(List.of(200L)).knowledgeBases()).hasSize(1);
            error(() -> service.resolve(List.of(200L)), ErrorCode.KB_403_FORBIDDEN);
        }
    }

    /** 验证权限继承及系统管理入口的管理员限制。 */
    @Test
    void shouldEnforcePermissionInheritanceAndReserveManagementForSystemAdmin() {
        assertThat(Permission.ADMIN.includes(Permission.WRITE)).isTrue();
        assertThat(Permission.WRITE.includes(Permission.READ)).isTrue();
        assertThat(Permission.READ.includes(Permission.WRITE)).isFalse();
        when(mapper.find(100, actor)).thenReturn(kb(100, 10, Permission.WRITE, KnowledgeBaseStatus.ENABLED));
        service.require(100, actor, Permission.WRITE, false);
        error(() -> service.require(100, actor, Permission.ADMIN, false), ErrorCode.KB_403_FORBIDDEN);
        error(() -> service.require(100, actor, Permission.READ, true), ErrorCode.AUTH_403_FORBIDDEN);
    }

    /** 构造指定状态和权限的知识库响应。 */
    private KnowledgeBaseResponse kb(long id, long department, Permission permission, KnowledgeBaseStatus status) {
        return new KnowledgeBaseResponse(
            id,
            "test",
            "",
            department,
            "dept",
            1L,
            "owner",
            "PRIVATE",
            status,
            "text-embedding-v3",
            1024,
            "qwen-plus",
            Instant.EPOCH,
            Instant.EPOCH,
            permission
        );
    }

    /** 提取或断言用例期望的公开业务错误。 */
    private void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, ex ->
            assertThat(ex.errorCode()).isEqualTo(code)
        );
    }
}
