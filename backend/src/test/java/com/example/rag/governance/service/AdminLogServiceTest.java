package com.example.rag.governance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.governance.config.AdminLogProperties;
import com.example.rag.governance.dto.AdminLogModels.*;
import com.example.rag.governance.mapper.GovernanceMapper;
import com.example.rag.user.domain.UserAccount;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminLogServiceTest {

    private final GovernanceMapper mapper = mock(GovernanceMapper.class);
    private final PermissionScopeService permissions = mock(PermissionScopeService.class);
    private final UserAccount actor = mock(UserAccount.class);
    private final AdminLogService service = new AdminLogService(
        mapper,
        permissions,
        new AdminLogProperties(ZoneId.of("Asia/Shanghai"), 366)
    );

    /** 为每个查询提供明确的当前账号，验证权限复查使用同一身份。 */
    @BeforeEach
    void prepareIdentity() {
        when(permissions.currentUser()).thenReturn(actor);
    }

    /** 日期包含北京时间结束日，两个 SQL 共用精确匹配筛选与稳定分页。 */
    @Test
    void shouldNormalizeAuditFiltersAndIncludeWholeEndDate() {
        when(mapper.auditLogs(any(), eq(20), eq(20L))).thenReturn(List.of());
        when(mapper.countAuditLogs(any())).thenReturn(23L);
        AuditQuery query = new AuditQuery(
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 6),
            5L,
            " request-1 ",
            " PUT:/api/v1/knowledge-bases/:id "
        );
        PageResponse<AuditLog> logs = service.audits(query, new PageRequest(2, 20));
        assertThat(logs.total()).isEqualTo(23);
        ArgumentCaptor<LogFilter> filters = ArgumentCaptor.forClass(LogFilter.class);
        verify(mapper).auditLogs(filters.capture(), eq(20), eq(20L));
        LogFilter filter = filters.getValue();
        assertThat(filter.from()).isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
        assertThat(filter.to()).isEqualTo(Instant.parse("2026-10-06T16:00:00Z"));
        assertThat(filter.requestId()).isEqualTo("request-1");
        assertThat(filter.action()).isEqualTo("PUT:/api/v1/knowledge-bases/:id");
        verify(mapper).countAuditLogs(filter);
        verify(permissions).requireSystemAdmin(actor);
    }

    /** 身份检查失败时不执行任何日志查询，避免服务层调用绕过管理员权限。 */
    @Test
    void shouldDenyLogQueriesBeforeReadingDatabase() {
        doThrow(new BusinessException(ErrorCode.AUTH_403_FORBIDDEN)).when(permissions).requireSystemAdmin(actor);
        RequestQuery query = new RequestQuery(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 6), null, null, null);
        assertThatThrownBy(() -> service.requests(query, new PageRequest(1, 20))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    /** 拒绝反向与超预算日期范围，配置允许的完整窗口可以查询。 */
    @Test
    void shouldRejectInvalidDateRangesAndAllowConfiguredBoundary() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        for (LocalDate to : List.of(from.minusDays(1), from.plusDays(366))) {
            RequestQuery query = new RequestQuery(from, to, null, null, null);
            assertThatThrownBy(() -> service.requests(query, new PageRequest(1, 20))).isInstanceOf(
                BusinessException.class
            );
        }
        verifyNoInteractions(mapper);
        when(mapper.requestLogs(any(), anyInt(), anyLong())).thenReturn(List.of());
        service.requests(new RequestQuery(from, from.plusDays(365), null, "  ", null), new PageRequest(1, 20));
        ArgumentCaptor<LogFilter> filters = ArgumentCaptor.forClass(LogFilter.class);
        verify(mapper).requestLogs(filters.capture(), eq(20), eq(0L));
        assertThat(filters.getValue().requestId()).isNull();
    }
}
