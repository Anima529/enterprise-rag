package com.example.rag.auth.controller;

import com.example.rag.auth.dto.LoginRequest;
import com.example.rag.auth.dto.LoginResponse;
import com.example.rag.auth.service.AuthApplicationService;
import com.example.rag.common.api.ApiResponse;
import com.example.rag.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthApplicationService service;

    /** 配置请求入口所依赖的应用服务。 */
    public AuthController(AuthApplicationService service) {
        this.service = service;
    }

    /** 验证账号并返回登录凭证。 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse loginResponse = service.login(request);
        return ApiResponse.success(loginResponse);
    }

    /** 注销当前登录会话。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        service.logout();
        return ApiResponse.success(null);
    }

    /** 获取当前登录用户。 */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        UserResponse user = service.me();
        return ApiResponse.success(user);
    }
}
