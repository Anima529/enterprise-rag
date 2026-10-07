package com.example.rag.governance.audit;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.governance.mapper.GovernanceMapper;
import jakarta.servlet.http.*;
import java.util.Set;
import org.slf4j.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/** 对已授权且成功的知识库、ACL、文档和价格变更记录审计，正文与凭据不进入日志。 */
@Configuration
public class SensitiveOperationAudit implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SensitiveOperationAudit.class);
    private final GovernanceMapper mapper;

    /** 注入持久审计来源，避免只有进程日志而无法追溯操作。 */
    public SensitiveOperationAudit(GovernanceMapper mapper) {
        this.mapper = mapper;
    }

    /** 注册管理资源写入拦截器，身份由前置安全拦截器校验。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
            .addInterceptor(
                new HandlerInterceptor() {
                    /** 写入完成后仅记录成功变更，异步聊天及认证入口不在此审计范围。 */
                    @Override
                    public void afterCompletion(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Object handler,
                        Exception exception
                    ) {
                        if (
                            !Set.of(HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.DELETE.name()).contains(
                                request.getMethod()
                            ) ||
                            !org.springframework.http.HttpStatus.valueOf(response.getStatus()).is2xxSuccessful() ||
                            !StpUtil.isLogin()
                        ) {
                            return;
                        }
                        try {
                            mapper.audit(
                                StpUtil.getLoginIdAsLong(),
                                request.getMethod() + ":" + request.getRequestURI().replaceAll("/[0-9]+", "/:id"),
                                null,
                                MDC.get(TraceContextKeys.REQUEST_ID),
                                MDC.get(TraceContextKeys.TRACE_ID)
                            );
                        } catch (RuntimeException failure) {
                            log.error(
                                "audit persistence failed, requestId={}, traceId={}, exceptionType={}",
                                MDC.get(TraceContextKeys.REQUEST_ID),
                                MDC.get(TraceContextKeys.TRACE_ID),
                                failure.getClass().getSimpleName()
                            );
                        }
                    }
                }
            )
            .addPathPatterns(
                "/api/v1/knowledge-bases/**",
                "/api/v1/admin/knowledge-bases/**",
                "/api/v1/documents/**",
                "/api/v1/admin/usage/prices"
            );
    }
}
