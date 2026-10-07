package com.example.rag.common.security;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.user.service.UserApplicationService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfiguration implements WebMvcConfigurer {

    public static final String CHAT_STREAM_OPENED = SecurityConfiguration.class.getName() + ".chatStreamOpened";
    private final UserApplicationService users;

    /** 配置安全拦截器所需的有效账号查询依赖。 */
    public SecurityConfiguration(UserApplicationService users) {
        this.users = users;
    }

    /** 为业务入口注册登录及角色校验，排除允许匿名访问的路径。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
            .addInterceptor(
                initialRequest(
                    new SaInterceptor(handler -> {
                        boolean publicStatus =
                            "/api/v1/system/status".equals(SaHolder.getRequest().getRequestPath()) &&
                            HttpMethod.GET.name().equals(SaHolder.getRequest().getMethod());
                        boolean publicLogin =
                            "/api/v1/auth/login".equals(SaHolder.getRequest().getRequestPath()) &&
                            HttpMethod.POST.name().equals(SaHolder.getRequest().getMethod());
                        if (!publicStatus && !publicLogin) {
                            StpUtil.checkLogin();
                            users.requireActive(StpUtil.getLoginIdAsLong());
                        }
                        SaRouter.match("/api/v1/admin/**", route -> StpUtil.checkRole(AccountProtocol.SYSTEM_ADMIN));
                    }).isAnnotation(false)
                )
            )
            .addPathPatterns("/api/v1/**");
        // 注解角色校验前必须先检查账号有效性；停用账号返回 401。
        registry.addInterceptor(initialRequest(new SaInterceptor())).addPathPatterns("/api/v1/**");
    }

    /** 包装安全拦截器，避免异步再次派发时重复校验初始请求。 */
    private HandlerInterceptor initialRequest(SaInterceptor delegate) {
        return new HandlerInterceptor() {
            /** 仅对初始派发执行安全拦截，异步派发沿用初始认证结果。 */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
                // 只有已授权并打开的 SSE 完成分派可以跳过 REST 身份认证。
                // 流式工作线程在每次发送事件前独立复查账号状态和知识库权限。
                if (
                    request.getDispatcherType() == DispatcherType.ASYNC &&
                    HttpMethod.POST.name().equals(request.getMethod()) &&
                    "/api/v1/chat/stream".equals(request.getRequestURI()) &&
                    Boolean.TRUE.equals(request.getAttribute(CHAT_STREAM_OPENED))
                ) {
                    return true;
                }
                return delegate.preHandle(request, response, handler);
            }
        };
    }
}
