package com.example.rag.user.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.security.AccountProtocol;
import com.example.rag.user.dto.UserResponse;
import com.example.rag.user.service.UserApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@SaCheckRole(AccountProtocol.SYSTEM_ADMIN)
public class UserController {

    private final UserApplicationService service;

    /** 配置请求入口所依赖的应用服务。 */
    public UserController(UserApplicationService service) {
        this.service = service;
    }

    /** 分页查询用户目录。 */
    @GetMapping
    public ApiResponse<PageResponse<UserResponse>> list(@Valid @ModelAttribute PageRequest request) {
        PageResponse<UserResponse> pageResponse = service.list(request);
        return ApiResponse.success(pageResponse);
    }
}
