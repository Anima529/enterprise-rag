package com.example.rag.auth.controller;

import static org.assertj.core.api.Assertions.*;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.auth.service.seed.DevSeedService;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("dev")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "rag.seed.enabled=false", "rag.index-task.enabled=false" }
)
class AuthInfrastructureIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordService passwords;

    @Autowired
    UserMapper users;

    @Autowired
    SaTokenDao tokenDao;

    @Autowired
    DevSeedService seed;

    private long departmentId;
    private long userId;
    private long adminId;
    private String username;
    private String adminName;
    private String password;

    /** 创建隔离的部门与账号，供真实认证集成测试使用。 */
    @BeforeEach
    void createIsolatedAccounts() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        username = "it.user." + suffix;
        adminName = "it.admin." + suffix;
        password = UUID.randomUUID().toString();
        departmentId = jdbc.queryForObject(
            "INSERT INTO sys_department(code, name) VALUES (?, ?) RETURNING id",
            Long.class,
            "IT_" + suffix.toUpperCase(),
            "集成测试部门"
        );
        String hash = passwords.hash(password);
        userId = insert(username, hash, "USER");
        adminId = insert(adminName, hash, "SYSTEM_ADMIN");
    }

    /** 清理本组用例创建的数据、会话或对象，保留既有基础设施。 */
    @AfterEach
    void cleanup() {
        StpUtil.logout(userId);
        StpUtil.logout(adminId);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?, ?)", userId, adminId);
        jdbc.update("DELETE FROM sys_department WHERE id = ?", departmentId);
    }

    /** 验证真实 BCrypt 凭据持久化及登录、角色边界。 */
    @Test
    void shouldPersistBcryptCredentialsAndEnforceRealLoginAndRoleBoundaries() {
        UserAccount account = users.findByUsername(username);
        assertThat(account.passwordHash()).hasSize(60).isNotEqualTo(password);
        assertThat(passwords.matches(password, account.passwordHash())).isTrue();
        assertThat(login(username, "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login("missing.user", password).getBody().path("code").asText()).isEqualTo(
            "AUTH_401_INVALID_CREDENTIALS"
        );
        assertThat(get("/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String ordinary = token(username);
        assertThat(get("/auth/me", ordinary).getBody().at("/data/departmentId").asLong()).isEqualTo(departmentId);
        assertThat(get("/admin/users", ordinary).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/admin/departments", ordinary).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String privileged = token(adminName);
        ResponseEntity<JsonNode> directory = get("/admin/users?page=1&pageSize=100", privileged);
        assertThat(directory.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(directory.getBody().toString()).doesNotContain(
            "passwordHash",
            "password_hash",
            account.passwordHash()
        );
        assertThat(get("/admin/departments", privileged).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/admin/users?page=0", privileged).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/admin/users?pageSize=101", privileged).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/admin/users?page=2147483647", privileged).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** 通过真实 HTTP 与持久日志验证元数据响应、管理员边界和角色即时撤销。 */
    @Test
    void shouldExposeLogMetadataOnlyToCurrentAdministrators() {
        String ordinary = token(username);
        String privileged = token(adminName);
        String requestId = UUID.randomUUID().toString();
        long auditId = jdbc.queryForObject(
            "INSERT INTO audit_log(user_id, action, request_id, trace_id, created_at) " +
                "VALUES (?, 'EVALUATION_DATASET_CREATE', ?, 'trace-log-test', '2026-10-06T00:00:00Z') RETURNING id",
            Long.class,
            userId,
            requestId
        );
        try {
            jdbc.update(
                "INSERT INTO rag_request_trace(request_id, trace_id, user_id, department_id, status, created_at) " +
                    "VALUES (?, 'trace-log-test', ?, ?, 'RUNNING', '2026-10-06T00:00:00Z')",
                requestId,
                userId,
                departmentId
            );
            String query = "?from=2026-10-06&to=2026-10-06&requestId=" + requestId;
            for (String path : new String[] { "/admin/logs/audit", "/admin/logs/requests" }) {
                assertThat(get(path + query, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(get(path + query, ordinary).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                ResponseEntity<JsonNode> response = get(path + query, privileged);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody().at("/data/total").asInt()).isEqualTo(1);
                assertThat(response.getBody().at("/data/records/0/username").asText()).isEqualTo(username);
                assertThat(response.getBody().at("/data/records/0/traceId").asText()).isEqualTo("trace-log-test");
                assertThat(response.getBody().toString()).doesNotContain("passwordHash", "password_hash", "content");
            }
            jdbc.update("UPDATE sys_user SET role = 'USER' WHERE id = ?", adminId);
            assertThat(get("/admin/logs/audit" + query, privileged).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            jdbc.update("DELETE FROM rag_request_trace WHERE request_id = ?", requestId);
            jdbc.update("DELETE FROM audit_log WHERE id = ?", auditId);
        }
    }

    /** 验证 Redis TTL 与单会话撤销。 */
    @Test
    void shouldUseRedisTtlAndRevokeOnlyCurrentSession() throws Exception {
        String first = token(username);
        String second = token(username);
        assertThat(first).isNotEqualTo(second);
        assertThat(tokenDao.getClass().getName()).contains("Redis");
        assertThat(tokenDao.getTimeout(StpUtil.getStpLogic().splicingKeyTokenValue(first))).isBetween(28780L, 28800L);
        assertThat(post("/auth/logout", first).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/auth/me", first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/auth/me", second).getStatusCode()).isEqualTo(HttpStatus.OK);
        StpUtil.renewTimeout(second, 1);
        Thread.sleep(1300);
        assertThat(get("/auth/me", second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String idle = token(username);
        tokenDao.update(
            StpUtil.getStpLogic().splicingKeyLastActiveTime(idle),
            Long.toString(System.currentTimeMillis() - 1801_000L)
        );
        assertThat(get("/auth/me", idle).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 验证停用、删除和降级账号的现有 Token 立即失去权限。 */
    @Test
    void shouldImmediatelyRejectDisabledDeletedAndDemotedAccountsWithExistingTokens() {
        String privileged = token(adminName);
        jdbc.update("UPDATE sys_user SET role = 'USER' WHERE id = ?", adminId);
        assertThat(get("/admin/users", privileged).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String ordinary = token(username);
        jdbc.update("UPDATE sys_department SET status = 'DISABLED' WHERE id = ?", departmentId);
        assertThat(get("/auth/me", ordinary).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/admin/departments", privileged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(username, password).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE sys_department SET status = 'ACTIVE' WHERE id = ?", departmentId);
        jdbc.update("UPDATE sys_user SET status = 'DISABLED' WHERE id = ?", userId);
        assertThat(get("/auth/me", ordinary).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE sys_user SET status = 'ACTIVE', deleted = true WHERE id = ?", userId);
        assertThat(get("/auth/me", ordinary).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 验证账号唯一键、外键、摘要、角色和安全整数约束。 */
    @Test
    void shouldEnforceUniqueForeignKeyHashRoleAndSafeIntegerConstraints() {
        String hash = users.findById(userId).passwordHash();
        assertThatThrownBy(() -> insert(username, hash, "USER")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO sys_department(code,name) SELECT code,name FROM sys_department WHERE id=?",
                departmentId
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE sys_user SET password_hash = 'plaintext' WHERE id = ?", userId)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE sys_user SET department_id = 9007199254740991 WHERE id = ?", userId)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE sys_user SET role = 'UNRECOGNIZED' WHERE id = ?", userId)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE sys_user SET id = 9007199254740992 WHERE id = ?", userId)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 验证两个部门和三个账号的种子幂等且不重置密码。 */
    @Test
    void shouldSeedTwoDepartmentsAndThreeUsersIdempotentlyWithoutResettingPasswords() {
        String random = UUID.randomUUID().toString();
        seed.initialize("123456", "123456", "123456");
        UserAccount admin = users.findByUsername("admin");
        UserAccount alpha = users.findByUsername("alpha.user");
        UserAccount beta = users.findByUsername("beta.user");
        assertThat(admin.role()).isEqualTo("SYSTEM_ADMIN");
        assertThat(alpha.role()).isEqualTo("USER");
        assertThat(beta.role()).isEqualTo("USER");
        assertThat(alpha.departmentId()).isNotEqualTo(beta.departmentId());
        seed.initialize(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertThat(users.findByUsername("admin")).isEqualTo(admin);
        assertThat(users.findByUsername("alpha.user")).isEqualTo(alpha);
        assertThat(users.findByUsername("beta.user")).isEqualTo(beta);
        assertThatThrownBy(() -> seed.initialize("short", random, random)).isInstanceOf(IllegalStateException.class);
    }

    /** 写入认证用例所需的带 BCrypt 摘要测试账号。 */
    private long insert(String name, String hash, String role) {
        return jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id,role) VALUES (?,?,?,?) RETURNING id",
            Long.class,
            name,
            hash,
            departmentId,
            role
        );
    }

    /** 通过真实登录入口获取指定测试账号的响应。 */
    private ResponseEntity<JsonNode> login(String name, String raw) {
        return http.postForEntity("/api/v1/auth/login", Map.of("username", name, "password", raw), JsonNode.class);
    }

    /** 从成功登录响应提取 Token。 */
    private String token(String name) {
        ResponseEntity<JsonNode> result = login(name, password);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return result.getBody().at("/data/token").asText();
    }

    /** 发送带测试认证信息的 GET 请求并返回可断言响应。 */
    private ResponseEntity<JsonNode> get(String path, String token) {
        return http.exchange("/api/v1" + path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    /** 发送带测试认证信息的 POST 请求并返回可断言响应。 */
    private ResponseEntity<JsonNode> post(String path, String token) {
        return http.exchange("/api/v1" + path, HttpMethod.POST, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    /** 构造本用例请求所需的认证及内容类型请求头。 */
    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.set("satoken", token);
        }
        return headers;
    }
}
