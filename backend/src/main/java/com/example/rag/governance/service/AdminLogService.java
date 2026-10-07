package com.example.rag.governance.service;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.governance.config.AdminLogProperties;
import com.example.rag.governance.domain.RequestLogStatus;
import com.example.rag.governance.dto.AdminLogModels.*;
import com.example.rag.governance.mapper.GovernanceMapper;
import com.example.rag.user.domain.UserAccount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/** 仅允许实时有效的系统管理员读取全局脱敏日志。 */
@Service
public class AdminLogService {

    private final GovernanceMapper mapper;
    private final PermissionScopeService permissions;
    private final AdminLogProperties config;

    /** 注入已有日志来源、实时权限与时间范围配置。 */
    public AdminLogService(GovernanceMapper mapper, PermissionScopeService permissions, AdminLogProperties config) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.config = config;
    }

    /** 分页读取成功敏感操作审计，历史记录允许保留已删除用户的空身份。 */
    public PageResponse<AuditLog> audits(AuditQuery query, PageRequest page) {
        requireAdministrator();
        LogFilter filter = filter(query.from(), query.to(), query.userId(), query.requestId(), query.action(), null);
        List<AuditLog> records = mapper.auditLogs(filter, page.pageSize(), page.offset());
        long total = mapper.countAuditLogs(filter);
        return new PageResponse<>(records, total, page.page(), page.pageSize());
    }

    /** 分页读取请求状态和耗时，只公开追踪元数据，不扩大会话正文访问范围。 */
    public PageResponse<RequestLog> requests(RequestQuery query, PageRequest page) {
        requireAdministrator();
        LogFilter filter = filter(query.from(), query.to(), query.userId(), query.requestId(), null, query.status());
        List<RequestLog> records = mapper.requestLogs(filter, page.pageSize(), page.offset());
        long total = mapper.countRequestLogs(filter);
        return new PageResponse<>(records, total, page.page(), page.pageSize());
    }

    /** Service 层再次检查数据库中的当前身份，角色撤销立即生效。 */
    private void requireAdministrator() {
        UserAccount actor = permissions.currentUser();
        permissions.requireSystemAdmin(actor);
    }

    /** 日期按配置时区包含结束日，并将查询跨度限制在配置预算内。 */
    private LogFilter filter(
        LocalDate from,
        LocalDate to,
        Long userId,
        String requestId,
        String action,
        RequestLogStatus status
    ) {
        if (
            from == null ||
            to == null ||
            from.isAfter(to) ||
            ChronoUnit.DAYS.between(from, to) >= config.maxQueryDays() ||
            to.equals(LocalDate.MAX)
        ) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        Instant start = from.atStartOfDay(config.timezone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(config.timezone()).toInstant();
        return new LogFilter(start, end, userId, normalized(requestId), normalized(action), status);
    }

    /** 空筛选不加数据库条件，其余标识采用精确匹配。 */
    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
