package com.example.rag.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.security.AccountProtocol;
import com.example.rag.governance.dto.AdminLogModels.*;
import com.example.rag.governance.service.AdminLogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/logs")
@SaCheckRole(AccountProtocol.SYSTEM_ADMIN)
public class AdminLogController {

    private final AdminLogService service;

    /** 注入仅公开脱敏元数据的管理员日志查询服务。 */
    public AdminLogController(AdminLogService service) {
        this.service = service;
    }

    /** 校验时间及分页参数后返回管理员操作审计。 */
    @GetMapping("/audit")
    public ApiResponse<PageResponse<AuditLog>> audits(
        @Valid @ModelAttribute AuditQuery query,
        @Valid @ModelAttribute PageRequest page
    ) {
        PageResponse<AuditLog> logs = service.audits(query, page);
        return ApiResponse.success(logs);
    }

    /** 校验状态和时间范围后返回 RAG 请求追踪，保持会话内容权限边界。 */
    @GetMapping("/requests")
    public ApiResponse<PageResponse<RequestLog>> requests(
        @Valid @ModelAttribute RequestQuery query,
        @Valid @ModelAttribute PageRequest page
    ) {
        PageResponse<RequestLog> logs = service.requests(query, page);
        return ApiResponse.success(logs);
    }
}
