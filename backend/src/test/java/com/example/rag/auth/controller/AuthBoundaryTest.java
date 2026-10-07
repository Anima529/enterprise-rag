package com.example.rag.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.department.mapper.DepartmentMapper;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "rag.index-task.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState",
        "POSTGRES_PASSWORD=test-only",
        "REDIS_PASSWORD=test-only",
        "MINIO_ACCESS_KEY=test-only",
        "MINIO_SECRET_KEY=test-only",
        "rag.auth.bcrypt-strength=10",
    }
)
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
@Import(AuthBoundaryTest.MemorySessions.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthBoundaryTest {

    @MockitoBean
    com.example.rag.governance.mapper.GovernanceMapper governance;

    @MockitoBean
    com.example.rag.evaluation.mapper.EvaluationMapper evaluationMapper;

    @MockitoBean
    com.example.rag.usage.mapper.UsageMapper usageMapper;

    @MockitoBean
    com.example.rag.governance.service.RedisCostGuard guard;

    @MockitoBean
    com.example.rag.governance.service.SemanticAnswerCache cache;

    @MockitoBean
    com.example.rag.evaluation.worker.EvaluationWorker evaluationWorker;

    @MockitoBean
    com.example.rag.usage.repository.UsageRepository usageRepository;

    @MockitoBean
    com.example.rag.rag.retrieve.mapper.KeywordSearchMapper keywordSearch;

    @MockitoBean
    com.example.rag.rag.retrieve.mapper.KeywordIndexMapper keywordIndex;

    @MockitoBean
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockitoBean
    com.example.rag.conversation.mapper.ConversationMapper conversationMapper;

    @MockitoBean
    com.example.rag.document.mapper.DocumentVersionMapper versionMapper;

    @TestConfiguration
    static class MemorySessions {

        /** 提供内存 Token 存储，隔离真实 Redis 对测试的影响。 */
        @Bean
        @Primary
        SaTokenDao memoryTokenDao() {
            return new SaTokenDaoDefaultImpl();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PasswordService passwords;

    @Autowired
    SaTokenDao tokenDao;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @MockitoBean
    UserMapper users;

    @MockitoBean
    com.example.rag.document.mapper.DocumentMapper documents;

    @MockitoBean
    com.example.rag.document.repository.index.IndexTaskStore indexTasks;

    @MockitoBean
    com.example.rag.document.repository.parser.ParsedDocumentRepository parsedDocuments;

    @MockitoBean
    com.example.rag.document.repository.chunk.DocumentChunkRepository chunks;

    @MockitoBean
    com.example.rag.document.repository.embedding.DocumentEmbeddingRepository embeddings;

    @MockitoBean
    com.example.rag.rag.retrieve.repository.VectorSearchRepository vectors;

    @MockitoBean
    com.example.rag.document.service.DocumentApplicationService documentService;

    @MockitoBean
    DepartmentMapper departments;

    @MockitoBean
    com.example.rag.knowledgebase.mapper.KnowledgeBaseMapper knowledgeBases;

    @MockitoBean
    com.example.rag.auth.mapper.seed.DevSeedMapper seed;

    @MockitoBean
    com.example.rag.system.mapper.SystemMapper system;

    private static final String PASSWORD = "test-only-password-123";
    private UserAccount user;
    private UserAccount admin;

    /** 为 Web 边界测试配置可控的账号状态。 */
    @BeforeEach
    void accounts() {
        String hash = passwords.hash(PASSWORD);
        user = account(101, "alpha.user", "USER", "ACTIVE", false, "ACTIVE", false, hash);
        admin = account(1, "admin", "SYSTEM_ADMIN", "ACTIVE", false, "ACTIVE", false, hash);
        when(users.findByUsername(user.username())).thenReturn(user);
        when(users.findByUsername(admin.username())).thenReturn(admin);
        when(users.findById(101)).thenReturn(user);
        when(users.findById(1)).thenReturn(admin);
    }

    /** 在用例结束后撤销测试 Token，避免跨用例污染。 */
    @AfterEach
    void revoke() {
        StpUtil.logout(101L);
        StpUtil.logout(1L);
    }

    /** 验证非开发环境不会启用演示种子。 */
    @Test
    void shouldLeaveDevelopmentSeedDisabledOutsideDevProfile() {
        assertThat(context.getBeansOfType(com.example.rag.auth.initializer.seed.DevSeedInitializer.class)).isEmpty();
        assertThat(context.getBeansOfType(com.example.rag.auth.service.seed.DevSeedService.class)).isEmpty();
    }

    /** 验证登录及当前用户响应不含密码摘要。 */
    @Test
    void shouldLoginAndReturnCurrentUserWithoutPasswordHash() throws Exception {
        String token = login("alpha.user", PASSWORD);
        assertThat(StpUtil.getTokenTimeout(token)).isBetween(28790L, 28800L);
        mvc.perform(get("/api/v1/auth/me").header("satoken", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("alpha.user"))
            .andExpect(jsonPath("$.data.roles[0]").value("USER"))
            .andExpect(jsonPath("$.data.departmentId").value(10))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    /** 验证错误密码和未知账号返回相同错误。 */
    @Test
    void shouldRejectWrongPasswordAndUnknownUserWithSameError() throws Exception {
        for (String name : new String[] { "alpha.user", "unknown.user" }) {
            mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username", name, "password", "wrong")))
            )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_401_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
    }

    /** 验证缺失、无效及非请求头 Token 被拒绝。 */
    @Test
    void shouldRejectMissingInvalidCookieQueryAndBodyTokens() throws Exception {
        String token = login("alpha.user", PASSWORD);
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("satoken", "invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("satoken", token)).param("satoken", token)).andExpect(
            status().isUnauthorized()
        );
        mvc.perform(
            post("/api/v1/auth/logout")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("satoken", token)))
        ).andExpect(status().isUnauthorized());
    }

    /** 验证真实目录接口限制管理员访问。 */
    @Test
    void shouldDenyOrdinaryUserAndAllowAdministratorOnRealDirectoryApis() throws Exception {
        String ordinary = login("alpha.user", PASSWORD);
        String privileged = login("admin", PASSWORD);
        for (String path : new String[] { "/api/v1/admin/users", "/api/v1/admin/departments" }) {
            mvc.perform(get(path).header("satoken", ordinary))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_403_FORBIDDEN"));
            mvc.perform(get(path).header("satoken", privileged)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/admin/users").header("satoken", privileged).param("pageSize", "101")).andExpect(
            status().isBadRequest()
        );
    }

    /** 日志入口拒绝匿名和普通账号，并校验分页、日期、标识及请求状态。 */
    @Test
    void shouldProtectAdminLogsAndValidateQueryBoundaries() throws Exception {
        String ordinary = login("alpha.user", PASSWORD);
        String privileged = login("admin", PASSWORD);
        for (String path : new String[] { "/api/v1/admin/logs/audit", "/api/v1/admin/logs/requests" }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("satoken", ordinary)).andExpect(status().isForbidden());
            mvc.perform(get(path).header("satoken", privileged).param("from", "2026-10-01").param("to", "2026-10-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20));
            for (Map<String, String> invalid : java.util.List.of(
                Map.of("pageSize", "101"),
                Map.of("page", "0"),
                Map.of("userId", "-1"),
                Map.of("userId", "9007199254740992"),
                Map.of("requestId", "x".repeat(121)),
                Map.of("from", "invalid"),
                Map.of("from", "2026-10-07"),
                Map.of("to", "2028-10-01")
            )) {
                org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = get(path)
                    .header("satoken", privileged)
                    .param("from", invalid.getOrDefault("from", "2026-10-01"))
                    .param("to", invalid.getOrDefault("to", "2026-10-06"));
                for (Map.Entry<String, String> parameter : invalid.entrySet()) {
                    if (!parameter.getKey().equals("from") && !parameter.getKey().equals("to")) {
                        request.param(parameter.getKey(), parameter.getValue());
                    }
                }
                mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_400_VALIDATION"));
            }
            mvc.perform(get(path).header("satoken", privileged)).andExpect(status().isBadRequest());
        }
        mvc.perform(
            get("/api/v1/admin/logs/requests")
                .header("satoken", privileged)
                .param("from", "2026-10-01")
                .param("to", "2026-10-06")
                .param("status", "UNKNOWN")
        ).andExpect(status().isBadRequest());
        mvc.perform(
            get("/api/v1/admin/logs/audit")
                .header("satoken", privileged)
                .param("from", "2026-10-01")
                .param("to", "2026-10-06")
                .param("action", "x".repeat(81))
        ).andExpect(status().isBadRequest());
    }

    /** 验证登出只撤销当前 Token，保留并发会话。 */
    @Test
    void shouldRevokeOnlyCurrentTokenAndKeepIndependentConcurrentSession() throws Exception {
        String first = login("alpha.user", PASSWORD);
        String second = login("alpha.user", PASSWORD);
        assertThat(first).isNotEqualTo(second);
        mvc.perform(post("/api/v1/auth/logout").header("satoken", first)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("satoken", first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("satoken", second)).andExpect(status().isOk());
    }

    /** 验证绝对过期和闲置过期的 Token 被拒绝。 */
    @Test
    void shouldRejectExpiredAndIdleTokens() throws Exception {
        String expired = login("alpha.user", PASSWORD);
        StpUtil.renewTimeout(expired, 1);
        Thread.sleep(1200);
        mvc.perform(get("/api/v1/auth/me").header("satoken", expired)).andExpect(status().isUnauthorized());
        String idle = login("alpha.user", PASSWORD);
        tokenDao.update(
            StpUtil.getStpLogic().splicingKeyLastActiveTime(idle),
            Long.toString(System.currentTimeMillis() - 1801_000L)
        );
        mvc.perform(get("/api/v1/auth/me").header("satoken", idle)).andExpect(status().isUnauthorized());
    }

    /** 验证账号、部门及角色调整立即作用于已有会话。 */
    @Test
    void shouldImmediatelyHonorAccountDepartmentAndRoleChanges() throws Exception {
        String token = login("admin", PASSWORD);
        when(users.findById(1)).thenReturn(
            account(1, "admin", "USER", "ACTIVE", false, "ACTIVE", false, admin.passwordHash())
        );
        mvc.perform(get("/api/v1/admin/users").header("satoken", token)).andExpect(status().isForbidden());
        for (UserAccount inactive : new UserAccount[] {
            account(1, "admin", "SYSTEM_ADMIN", "DISABLED", false, "ACTIVE", false, admin.passwordHash()),
            account(1, "admin", "SYSTEM_ADMIN", "ACTIVE", true, "ACTIVE", false, admin.passwordHash()),
            account(1, "admin", "SYSTEM_ADMIN", "ACTIVE", false, "DISABLED", false, admin.passwordHash()),
            account(1, "admin", "SYSTEM_ADMIN", "ACTIVE", false, "ACTIVE", true, admin.passwordHash()),
        }) {
            when(users.findById(1)).thenReturn(inactive);
            mvc.perform(get("/api/v1/auth/me").header("satoken", token)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/departments").header("satoken", token)).andExpect(status().isUnauthorized());
            when(users.findByUsername("admin")).thenReturn(inactive);
            mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username", "admin", "password", PASSWORD)))
            ).andExpect(status().isUnauthorized());
        }
    }

    /** 验证登录请求约束及密码 UTF-8 字节上限。 */
    @Test
    void shouldValidateLoginBodyAndUtf8Length() throws Exception {
        for (String body : new String[] {
            "{}",
            "{",
            "{\"username\":\"admin\",\"password\":\"\"}",
            json.writeValueAsString(Map.of("username", "admin", "password", "密".repeat(25))),
            json.writeValueAsString(Map.of("username", "admin", "password", PASSWORD, "role", "SYSTEM_ADMIN")),
        }) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_400_VALIDATION"));
        }
    }

    /** 通过真实登录入口获取指定测试账号的响应。 */
    private String login(String username, String password) throws Exception {
        MvcResult result = mvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username", username, "password", password)))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
            .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    /** 构造具有指定状态与角色的测试账号。 */
    private UserAccount account(
        long id,
        String name,
        String role,
        String status,
        boolean deleted,
        String departmentStatus,
        boolean departmentDeleted,
        String hash
    ) {
        return new UserAccount(
            id,
            name,
            hash,
            10L,
            "研发部",
            role,
            status,
            deleted,
            departmentStatus,
            departmentDeleted
        );
    }
}
