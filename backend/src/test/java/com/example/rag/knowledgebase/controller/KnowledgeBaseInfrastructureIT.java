package com.example.rag.knowledgebase.controller;

import static org.assertj.core.api.Assertions.*;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "rag.seed.enabled=false", "rag.index-task.enabled=false" }
)
@Import(KnowledgeBaseInfrastructureIT.ScopeProbe.class)
class KnowledgeBaseInfrastructureIT {

    @RestController
    static class ScopeProbe {

        private final PermissionScopeService permissions;

        /** 配置集成测试中用于验证检索授权范围的权限服务。 */
        ScopeProbe(PermissionScopeService permissions) {
            this.permissions = permissions;
        }

        /** 构造或查询用例所需的授权检索范围。 */
        @PostMapping("/api/v1/test/retrieval-scope")
        ApiResponse<Map<String, Object>> scope(@RequestBody List<Long> ids) {
            RetrievalScope scope = permissions.resolve(ids);
            return ApiResponse.success(Map.of("userId", scope.userId(), "knowledgeBases", scope.knowledgeBases()));
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordService passwords;

    @Autowired
    EmbeddingProperties embeddingConfig;

    @Autowired
    ChatProperties chatConfig;

    @Autowired
    javax.sql.DataSource dataSource;

    private long aDept;
    private long bDept;
    private long aUser;
    private long colleague;
    private long bUser;
    private long admin;
    private long aKb;
    private long bKb;
    private String aToken;
    private String colleagueToken;
    private String bToken;
    private String adminToken;
    private final List<Long> created = new ArrayList<>();

    /** 建立各用例所需的账号、知识库和文档测试数据。 */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        aDept = department("KA_" + suffix.toUpperCase());
        bDept = department("KB_" + suffix.toUpperCase());
        String hash = passwords.hash(UUID.randomUUID().toString());
        aUser = user("ka." + suffix, aDept, "USER", hash);
        colleague = user("kc." + suffix, aDept, "USER", hash);
        bUser = user("kb." + suffix, bDept, "USER", hash);
        admin = user("ks." + suffix, aDept, "SYSTEM_ADMIN", hash);
        aToken = StpUtil.createLoginSession(aUser);
        colleagueToken = StpUtil.createLoginSession(colleague);
        bToken = StpUtil.createLoginSession(bUser);
        adminToken = StpUtil.createLoginSession(admin);
        aKb = create(aToken, aDept, "A private");
        bKb = create(bToken, bDept, "B private");
    }

    /** 清理本组用例创建的数据、会话或对象，保留既有基础设施。 */
    @AfterEach
    void cleanup() {
        for (long id : created) {
            jdbc.update("DELETE FROM knowledge_base_acl WHERE knowledge_base_id = ?", id);
            jdbc.update("DELETE FROM knowledge_base WHERE id = ?", id);
        }
        for (long id : new long[] { aUser, colleague, bUser, admin }) {
            StpUtil.logout(id);
            jdbc.update("DELETE FROM sys_user WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM sys_department WHERE id IN (?, ?)", aDept, bDept);
    }

    /** 验证同部门默认仍私有且伪造知识库 ID 无法访问。 */
    @Test
    void shouldKeepDefaultPrivateEvenWithinDepartmentAndRejectForgedKbIds() {
        JsonNode own = get("/knowledge-bases/" + aKb, aToken, 200);
        assertThat(own.at("/data/permission").asText()).isEqualTo("ADMIN");
        assertThat(own.at("/data/visibility").asText()).isEqualTo("PRIVATE");
        assertThat(own.at("/data/embeddingModel").asText()).isEqualTo(embeddingConfig.model());
        assertThat(own.at("/data/embeddingDimensions").asInt()).isEqualTo(embeddingConfig.dimensions());
        assertThat(own.at("/data/generationModel").asText()).isEqualTo(chatConfig.model());
        assertThat(ids(get("/knowledge-bases", aToken, 200)))
            .contains(aKb)
            .doesNotContain(bKb);
        assertThat(ids(get("/knowledge-bases", colleagueToken, 200))).doesNotContain(aKb, bKb);
        for (String token : List.of(aToken, colleagueToken)) {
            error("/knowledge-bases/" + bKb, HttpMethod.GET, token, null, 403, "KB_403_FORBIDDEN");
            error(
                "/knowledge-bases/" + bKb,
                HttpMethod.PUT,
                token,
                update("forged", "ENABLED"),
                403,
                "KB_403_FORBIDDEN"
            );
            error("/knowledge-bases/" + bKb + "/acl", HttpMethod.GET, token, null, 403, "KB_403_FORBIDDEN");
            error("/knowledge-bases/" + bKb + "/acl", HttpMethod.PUT, token, acl(), 403, "KB_403_FORBIDDEN");
            error("/knowledge-bases/" + bKb, HttpMethod.DELETE, token, null, 403, "KB_403_FORBIDDEN");
        }
        assertThat(
            get("/knowledge-bases/" + bKb, bToken, 200)
                .at("/data/name")
                .asText()
        ).isEqualTo("B private");
        error("/knowledge-bases", HttpMethod.GET, null, null, 401, "AUTH_401_UNAUTHENTICATED");
        error("/knowledge-bases?pageSize=101", HttpMethod.GET, aToken, null, 400, "COMMON_400_VALIDATION");
    }

    /** 验证部门、用户、角色授权继承及即时撤销。 */
    @Test
    void shouldApplyDepartmentUserAndRoleGrantsWithInheritanceAndImmediateRevocation() {
        putAcl(aKb, aToken, entry("DEPARTMENT", aDept, "READ"));
        assertThat(
            get("/knowledge-bases/" + aKb, colleagueToken, 200)
                .at("/data/permission")
                .asText()
        ).isEqualTo("READ");
        error(
            "/knowledge-bases/" + aKb,
            HttpMethod.PUT,
            colleagueToken,
            update("no", "ENABLED"),
            403,
            "KB_403_FORBIDDEN"
        );
        putAcl(bKb, bToken, entry("USER", aUser, "WRITE"));
        assertThat(
            get("/knowledge-bases/" + bKb, aToken, 200)
                .at("/data/permission")
                .asText()
        ).isEqualTo("WRITE");
        request("/knowledge-bases/" + bKb, HttpMethod.PUT, aToken, update("shared edit", "ENABLED"), 200);
        error(
            "/knowledge-bases/" + bKb,
            HttpMethod.PUT,
            aToken,
            update("shared edit", "DISABLED"),
            403,
            "KB_403_FORBIDDEN"
        );
        error("/knowledge-bases/" + bKb + "/acl", HttpMethod.GET, aToken, null, 403, "KB_403_FORBIDDEN");
        putAcl(bKb, bToken, entry("ROLE", "USER", "ADMIN"));
        assertThat(
            get("/knowledge-bases/" + bKb, aToken, 200)
                .at("/data/permission")
                .asText()
        ).isEqualTo("ADMIN");
        putAcl(bKb, aToken); // ACL 管理员可以撤销自己的授权，但不能移除所有者权限。
        error("/knowledge-bases/" + bKb, HttpMethod.GET, aToken, null, 403, "KB_403_FORBIDDEN");
        assertThat(ids(get("/knowledge-bases", aToken, 200))).doesNotContain(bKb);
        assertThat(
            get("/knowledge-bases/" + bKb, bToken, 200)
                .at("/data/permission")
                .asText()
        ).isEqualTo("ADMIN");
    }

    /** 验证跨部门配对保持正确，混合或撤销范围整体拒绝。 */
    @Test
    void shouldPreserveCrossDepartmentPairsAndRejectWholeMixedOrRevokedRetrievalScope() {
        error("/test/retrieval-scope", HttpMethod.POST, aToken, List.of(), 403, "RAG_403_EMPTY_SCOPE");
        error("/test/retrieval-scope", HttpMethod.POST, aToken, List.of(aKb, bKb), 403, "KB_403_FORBIDDEN");
        putAcl(bKb, bToken, entry("USER", aUser, "READ"));
        JsonNode resolved = request("/test/retrieval-scope", HttpMethod.POST, aToken, List.of(bKb, aKb, bKb), 200).path(
            "data"
        );
        assertThat(resolved.path("userId").asLong()).isEqualTo(aUser);
        assertThat(resolved.path("knowledgeBases")).hasSize(2);
        assertThat(resolved.at("/knowledgeBases/0/knowledgeBaseId").asLong()).isEqualTo(bKb);
        assertThat(resolved.at("/knowledgeBases/0/departmentId").asLong()).isEqualTo(bDept);
        putAcl(bKb, bToken);
        error("/test/retrieval-scope", HttpMethod.POST, aToken, List.of(aKb, bKb), 403, "KB_403_FORBIDDEN");
    }

    /** 验证管理员入口受限，修复停用知识库不使其自动可读。 */
    @Test
    void shouldRestrictAdministratorApisAndRepairDisabledKbWithoutMakingItReadable() {
        for (String path : List.of(
            "/admin/knowledge-bases",
            "/admin/knowledge-bases/" + aKb,
            "/admin/knowledge-bases/" + aKb + "/acl"
        )) {
            error(path, HttpMethod.GET, aToken, null, 403, "AUTH_403_FORBIDDEN");
        }
        error(
            "/admin/knowledge-bases/" + aKb,
            HttpMethod.PUT,
            aToken,
            update("bad", "DISABLED"),
            403,
            "AUTH_403_FORBIDDEN"
        );
        error("/admin/knowledge-bases/" + aKb, HttpMethod.DELETE, aToken, null, 403, "AUTH_403_FORBIDDEN");
        error("/admin/knowledge-bases/" + aKb + "/acl", HttpMethod.PUT, aToken, acl(), 403, "AUTH_403_FORBIDDEN");
        request("/knowledge-bases/" + aKb, HttpMethod.PUT, aToken, update("disabled", "DISABLED"), 200);
        assertThat(ids(get("/knowledge-bases", adminToken, 200))).doesNotContain(aKb);
        assertThat(ids(get("/admin/knowledge-bases?pageSize=100", adminToken, 200))).contains(aKb, bKb);
        for (String token : List.of(aToken, adminToken)) {
            error("/knowledge-bases/" + aKb, HttpMethod.GET, token, null, 409, "KB_409_DISABLED");
            error("/test/retrieval-scope", HttpMethod.POST, token, List.of(aKb), 409, "KB_409_DISABLED");
        }
        error("/knowledge-bases/" + aKb, HttpMethod.PUT, aToken, update("restore", "ENABLED"), 409, "KB_409_DISABLED");
        get("/admin/knowledge-bases/" + aKb + "/acl", adminToken, 200);
        request(
            "/admin/knowledge-bases/" + aKb + "/acl",
            HttpMethod.PUT,
            adminToken,
            acl(entry("USER", colleague, "READ")),
            200
        );
        request("/admin/knowledge-bases/" + aKb, HttpMethod.PUT, adminToken, update("restored", "ENABLED"), 200);
        get("/knowledge-bases/" + aKb, colleagueToken, 200);
    }

    /** 验证软删除从所有范围排除知识库，包括系统管理员。 */
    @Test
    void shouldSoftDeleteAndExcludeDeletedKbFromEveryScopeIncludingSystemAdmin() {
        putAcl(bKb, bToken, entry("USER", aUser, "READ"));
        request("/admin/knowledge-bases/" + bKb, HttpMethod.DELETE, adminToken, null, 200);
        assertThat(jdbc.queryForObject("SELECT deleted FROM knowledge_base WHERE id = ?", Boolean.class, bKb)).isTrue();
        for (String token : List.of(aToken, bToken, adminToken)) {
            error("/knowledge-bases/" + bKb, HttpMethod.GET, token, null, 404, "KB_404_NOT_FOUND");
            error("/test/retrieval-scope", HttpMethod.POST, token, List.of(bKb), 404, "KB_404_NOT_FOUND");
            assertThat(ids(get("/knowledge-bases", token, 200))).doesNotContain(bKb);
        }
        error("/admin/knowledge-bases/" + bKb, HttpMethod.GET, adminToken, null, 404, "KB_404_NOT_FOUND");
        error("/admin/knowledge-bases/" + bKb + "/acl", HttpMethod.PUT, adminToken, acl(), 404, "KB_404_NOT_FOUND");
        assertThat(ids(get("/admin/knowledge-bases?pageSize=100", adminToken, 200))).doesNotContain(bKb);
    }

    /** 验证无效或重复主体原子拒绝且不能伪造归属。 */
    @Test
    void shouldRejectInvalidAndDuplicateSubjectsAtomicallyAndPreventOwnershipSpoofing() {
        putAcl(aKb, aToken, entry("USER", colleague, "READ"));
        for (Object body : List.of(
            acl(entry("ROLE", "EMPLOYEE", "READ")),
            acl(entry("USER", "01", "READ")),
            acl(entry("USER", "9007199254740992", "READ")),
            acl(entry("USER", "9007199254740991", "READ")),
            acl(entry("DEPARTMENT", "9007199254740991", "READ")),
            acl(entry("USER", colleague, "READ"), entry("USER", colleague, "WRITE")),
            Map.of("entries", Collections.nCopies(101, entry("USER", colleague, "READ")))
        )) {
            error("/knowledge-bases/" + aKb + "/acl", HttpMethod.PUT, aToken, body, 400, "COMMON_400_VALIDATION");
            assertThat(get("/knowledge-bases/" + aKb + "/acl", aToken, 200).path("data")).hasSize(1);
        }
        error(
            "/knowledge-bases/" + aKb + "/acl",
            HttpMethod.PUT,
            aToken,
            "{\"entries\":[null]}",
            400,
            "COMMON_400_VALIDATION"
        );
        error(
            "/knowledge-bases",
            HttpMethod.POST,
            aToken,
            Map.of("name", "bad", "departmentId", bDept),
            403,
            "AUTH_403_FORBIDDEN"
        );
        error(
            "/knowledge-bases",
            HttpMethod.POST,
            aToken,
            Map.of("name", "bad", "departmentId", aDept, "ownerUserId", bUser),
            403,
            "AUTH_403_FORBIDDEN"
        );
        error(
            "/knowledge-bases",
            HttpMethod.POST,
            aToken,
            Map.of("name", " ", "departmentId", aDept),
            400,
            "COMMON_400_VALIDATION"
        );
        error(
            "/knowledge-bases/" + aKb,
            HttpMethod.PUT,
            aToken,
            Map.of("name", "x", "status", "ENABLED", "departmentId", bDept),
            400,
            "COMMON_400_VALIDATION"
        );
    }

    /** 验证数据库约束和时间戳维护不改变所有者。 */
    @Test
    void shouldEnforceDatabaseConstraintsAndTrackTimestampsWithoutChangingOwnership() {
        putAcl(aKb, aToken, entry("ROLE", "USER", "WRITE"));
        assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (?, 'ROLE', 'USER', 'READ')",
                aKb
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE knowledge_base SET visibility='PUBLIC' WHERE id=?", aKb)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE knowledge_base SET embedding_dimensions=1 WHERE id=?", aKb)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE knowledge_base_acl SET permission='OWNER' WHERE knowledge_base_id=?", aKb)
        ).isInstanceOf(DataIntegrityViolationException.class);
        request("/knowledge-bases/" + aKb, HttpMethod.PUT, colleagueToken, update("edited", "ENABLED"), 200);
        assertThat(
            jdbc.queryForObject("SELECT updated_at >= created_at FROM knowledge_base WHERE id=?", Boolean.class, aKb)
        ).isTrue();
        assertThat(
            get("/knowledge-bases/" + aKb, aToken, 200)
                .at("/data/ownerUserId")
                .asLong()
        ).isEqualTo(aUser);
    }

    /** 验证等待并发权限撤销后重新检查写权限。 */
    @Test
    void shouldRecheckWritePermissionAfterWaitingForConcurrentRevocation() throws Exception {
        putAcl(bKb, bToken, entry("USER", aUser, "WRITE"));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (
                PreparedStatement lock = connection.prepareStatement(
                    "SELECT id FROM knowledge_base WHERE id=? FOR UPDATE"
                )
            ) {
                lock.setLong(1, bKb);
                lock.executeQuery().close();
            }
            CompletableFuture<Void> pending = CompletableFuture.runAsync(() ->
                error(
                    "/knowledge-bases/" + bKb,
                    HttpMethod.PUT,
                    aToken,
                    update("revoked write", "ENABLED"),
                    403,
                    "KB_403_FORBIDDEN"
                )
            );
            try {
                boolean waiting = false;
                for (int attempt = 0; attempt < 100 && !waiting; attempt++) {
                    waiting = Boolean.TRUE.equals(
                        jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND regexp_replace(query, '[[:space:]]+', ' ', 'g') LIKE '%FROM knowledge_base WHERE id%FOR UPDATE%')",
                            Boolean.class
                        )
                    );
                    if (!waiting) {
                        Thread.sleep(50);
                    }
                }
                assertThat(waiting).as("write must be blocked behind the KB row lock").isTrue();
                try (
                    PreparedStatement revoke = connection.prepareStatement(
                        "DELETE FROM knowledge_base_acl WHERE knowledge_base_id=?"
                    )
                ) {
                    revoke.setLong(1, bKb);
                    revoke.executeUpdate();
                }
                connection.commit();
            } finally {
                connection.rollback();
            }
            pending.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(
            get("/knowledge-bases/" + bKb, bToken, 200)
                .at("/data/name")
                .asText()
        ).isEqualTo("B private");
    }

    /** 插入测试部门并返回生成的 ID。 */
    private long department(String code) {
        return jdbc.queryForObject(
            "INSERT INTO sys_department(code,name) VALUES (?,?) RETURNING id",
            Long.class,
            code,
            code
        );
    }

    /** 插入测试用户并返回生成的 ID。 */
    private long user(String name, long dept, String role, String hash) {
        return jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id,role) VALUES (?,?,?,?) RETURNING id",
            Long.class,
            name,
            hash,
            dept,
            role
        );
    }

    /** 通过真实知识库入口提交创建请求。 */
    private long create(String token, long dept, String name) {
        long id = request("/knowledge-bases", HttpMethod.POST, token, Map.of("name", name, "departmentId", dept), 200)
            .at("/data/id")
            .asLong();
        created.add(id);
        return id;
    }

    /** 通过真实知识库入口提交属性更新请求。 */
    private Map<String, Object> update(String name, String status) {
        return Map.of("name", name, "description", "test", "status", status);
    }

    /** 构造指定类型、主体和权限的 ACL 测试条目。 */
    private Map<String, Object> entry(String type, Object id, String permission) {
        return Map.of("subjectType", type, "subjectId", id.toString(), "permission", permission);
    }

    /** 读取知识库 ACL 的测试响应。 */
    @SafeVarargs
    private final Map<String, Object> acl(Map<String, Object>... entries) {
        return Map.of("entries", List.of(entries));
    }

    /** 通过真实入口完整替换知识库 ACL。 */
    @SafeVarargs
    private final void putAcl(long id, String token, Map<String, Object>... entries) {
        request("/knowledge-bases/" + id + "/acl", HttpMethod.PUT, token, acl(entries), 200);
    }

    /** 从分页知识库响应提取 ID，供可见范围断言。 */
    private List<Long> ids(JsonNode response) {
        var result = new ArrayList<Long>();
        response.at("/data/records").forEach(kb -> result.add(kb.path("id").asLong()));
        return result;
    }

    /** 发送带测试认证信息的 GET 请求并返回可断言响应。 */
    private JsonNode get(String path, String token, int status) {
        return request(path, HttpMethod.GET, token, null, status);
    }

    /** 提取或断言用例期望的公开业务错误。 */
    private void error(String path, HttpMethod method, String token, Object body, int status, String code) {
        assertThat(request(path, method, token, body, status).path("code").asText()).isEqualTo(code);
    }

    /** 构造包含 Token 和可选请求体的测试请求。 */
    private JsonNode request(String path, HttpMethod method, String token, Object body, int expected) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("satoken", token);
        }
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1" + path,
            method,
            new HttpEntity<>(body, headers),
            JsonNode.class
        );
        assertThat(response.getStatusCode().value()).as("%s %s", method, path).isEqualTo(expected);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        if (expected >= 400) {
            assertThat(response.getBody().path("requestId").asText()).isEqualTo(
                response.getHeaders().getFirst("X-Request-Id")
            );
        }
        return response.getBody();
    }
}
