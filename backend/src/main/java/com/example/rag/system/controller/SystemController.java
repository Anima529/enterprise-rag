package com.example.rag.system.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.system.dto.SystemStatusRequest;
import com.example.rag.system.dto.SystemStatusResponse;
import com.example.rag.system.service.SystemApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {

    private final SystemApplicationService service;

    /** 配置请求入口所依赖的应用服务。 */
    public SystemController(SystemApplicationService service) {
        this.service = service;
    }

    /** 查询系统状态和基础设施信息。 */
    @GetMapping("/api/v1/system/status")
    public ApiResponse<SystemStatusResponse> status(@Valid @ModelAttribute SystemStatusRequest request) {
        SystemStatusResponse systemStatus = service.status();
        return ApiResponse.success(systemStatus);
    }
}
