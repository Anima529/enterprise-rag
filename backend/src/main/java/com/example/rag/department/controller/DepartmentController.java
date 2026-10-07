package com.example.rag.department.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.security.AccountProtocol;
import com.example.rag.department.dto.DepartmentResponse;
import com.example.rag.department.service.DepartmentApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/departments")
@SaCheckRole(AccountProtocol.SYSTEM_ADMIN)
public class DepartmentController {

    private final DepartmentApplicationService service;

    /** 配置请求入口所依赖的应用服务。 */
    public DepartmentController(DepartmentApplicationService service) {
        this.service = service;
    }

    /** 分页查询部门目录。 */
    @GetMapping
    public ApiResponse<PageResponse<DepartmentResponse>> list(@Valid @ModelAttribute PageRequest request) {
        PageResponse<DepartmentResponse> pageResponse = service.list(request);
        return ApiResponse.success(pageResponse);
    }
}
