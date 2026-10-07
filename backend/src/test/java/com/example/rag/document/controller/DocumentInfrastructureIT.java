package com.example.rag.document.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.config.MinioProperties;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.mapper.DocumentMapper;
import com.example.rag.document.service.upload.OrphanObjectCleanup;
import com.example.rag.document.storage.upload.DocumentStorage;
import com.example.rag.document.support.DocumentFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "rag.seed.enabled=false",
        "rag.index-task.enabled=false",
        "rag.document.max-bytes=65536",
        "spring.servlet.multipart.max-file-size=65536B",
        "spring.servlet.multipart.max-request-size=73728B",
        "rag.document.download-ttl-seconds=60",
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentInfrastructureIT {

    private static final String SCHEMA = "phase911_upload_" + UUID.randomUUID().toString().replace("-", "");

    /** 为本组集成测试建立隔离数据库并执行迁移。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add(
            "spring.datasource.url",
            () ->
                "jdbc:postgresql://" +
                System.getenv("POSTGRES_HOST") +
                ":" +
                System.getenv("POSTGRES_PORT") +
                "/" +
                System.getenv("POSTGRES_DB") +
                "?currentSchema=" +
                SCHEMA +
                ",public"
        );
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    /** 删除本组测试创建的隔离 Schema。 */
    @AfterAll
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DocumentProperties documentProperties;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordService passwords;

    @Autowired
    MinioClient minio;

    @Autowired
    MinioProperties minioProperties;

    @Autowired
    OrphanObjectCleanup cleanup;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    DocumentMapper mapper;

    @MockitoSpyBean
    DocumentStorage storage;

    private long department;
    private long otherDepartment;
    private long owner;
    private long reader;
    private long outsider;
    private long admin;
    private long kb;
    private long otherKb;
    private String ownerToken;
    private String readerToken;
    private String outsiderToken;
    private String adminToken;

    /** 建立各用例所需的账号、知识库和文档测试数据。 */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        department = department("DU_" + suffix.toUpperCase());
        otherDepartment = department("DV_" + suffix.toUpperCase());
        String hash = passwords.hash(UUID.randomUUID().toString());
        owner = user("du." + suffix, department, "USER", hash);
        reader = user("dr." + suffix, otherDepartment, "USER", hash);
        outsider = user("do." + suffix, otherDepartment, "USER", hash);
        admin = user("da." + suffix, department, "SYSTEM_ADMIN", hash);
        ownerToken = StpUtil.createLoginSession(owner);
        readerToken = StpUtil.createLoginSession(reader);
        outsiderToken = StpUtil.createLoginSession(outsider);
        adminToken = StpUtil.createLoginSession(admin);
        kb = knowledgeBase(ownerToken, department);
        otherKb = knowledgeBase(outsiderToken, otherDepartment);
    }

    /** 撤销用例创建的会话并清理私有存储测试对象。 */
    @AfterEach
    void tearDown() throws Exception {
        // 只清理本测试创建的两个知识库前缀及其数据行。
        reset(mapper, storage);
        for (long id : new long[] { kb, otherKb }) {
            for (String key : objects(id)) {
                minio.removeObject(RemoveObjectArgs.builder().bucket(minioProperties.bucket()).object(key).build());
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                jdbc.update(
                    "DELETE FROM document_cleanup_task WHERE document_id IN (SELECT id FROM document WHERE knowledge_base_id=?)",
                    id
                );
                jdbc.update(
                    "DELETE FROM document_index_task WHERE document_id IN (SELECT id FROM document WHERE knowledge_base_id=?)",
                    id
                );
                jdbc.update(
                    "DELETE FROM document_version WHERE document_id IN (SELECT id FROM document WHERE knowledge_base_id=?)",
                    id
                );
                jdbc.update("DELETE FROM document WHERE knowledge_base_id=?", id);
                jdbc.update("DELETE FROM document_storage_cleanup WHERE object_key LIKE ?", "kb/" + id + "/%");
                jdbc.update("DELETE FROM knowledge_base_acl WHERE knowledge_base_id=?", id);
                jdbc.update("DELETE FROM knowledge_base WHERE id=?", id);
            });
        }
        for (long id : new long[] { owner, reader, outsider, admin }) {
            StpUtil.logout(id);
            jdbc.update("DELETE FROM sys_user WHERE id=?", id);
        }
        jdbc.update("DELETE FROM sys_department WHERE id IN (?,?)", department, otherDepartment);
    }

    /** 验证支持格式的上传生成一个版本和待执行任务，并可下载完整私有原文件。 */
    @Test
    void shouldAcceptSupportedFormatsPersistOneVersionAndPendingTaskAndDownloadExactPrivateBytes() throws Exception {
        for (DocumentFormat format : DocumentFormat.values()) {
            byte[] bytes = DocumentFixtures.content(format);
            JsonNode accepted = upload(kb, ownerToken, "文档." + format.extension(), format.mime(), bytes, 202).path(
                "data"
            );
            long id = accepted.path("documentId").asLong();
            assertThat(accepted.path("documentVersion").asInt()).isEqualTo(1);
            assertThat(accepted.path("status").asText()).isEqualTo("UPLOADED");
            JsonNode document = get("/documents/" + id, ownerToken, 200).path("data");
            assertThat(document.path("sourceFormat").asText()).isEqualTo(format.name());
            assertThat(document.has("objectKey")).isFalse();
            assertThat(document.path("sizeBytes").asLong()).isEqualTo(bytes.length);
            JsonNode tasks = get("/documents/" + id + "/tasks", ownerToken, 200).at("/data/records");
            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).path("status").asText()).isEqualTo("PENDING");
            JsonNode signed = get("/documents/" + id + "/download?version=1", ownerToken, 200).path("data");
            ResponseEntity<byte[]> downloaded = http.getForEntity(
                URI.create(signed.path("url").asText()),
                byte[].class
            );
            assertThat(downloaded.getStatusCode().value()).isEqualTo(200);
            assertThat(downloaded.getBody()).isEqualTo(bytes);
            assertThat(downloaded.getHeaders().getFirst("Content-Disposition")).contains(
                "attachment",
                "filename*=UTF-8''"
            );
            assertThat(downloaded.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
            String key = mapper.objectKey(id, 1);
            assertThat(key).matches("kb/" + kb + "/document/" + id + "/v1/[a-f0-9-]+\\." + format.extension());
            assertThat(
                http
                    .getForEntity(
                        URI.create(minioProperties.endpoint() + "/" + minioProperties.bucket() + "/" + key),
                        String.class
                    )
                    .getStatusCode()
                    .value()
            ).isEqualTo(403);
        }
        assertThat(
            get("/knowledge-bases/" + kb + "/documents", ownerToken, 200)
                .at("/data/total")
                .asInt()
        ).isEqualTo(DocumentFormat.values().length);
        assertThat(journalCount()).isZero();
    }

    /** 验证同库同内容并发上传去重，允许同名不同内容及跨库内容。 */
    @Test
    void shouldDeduplicateConcurrentSameKbUploadsButAllowSameNameDifferentContentAndCrossKb() throws Exception {
        byte[] bytes = "concurrent 中文".getBytes(StandardCharsets.UTF_8);
        CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() ->
            uploadAny(kb, ownerToken, "a.md", "text/plain", bytes)
        );
        CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(() ->
            uploadAny(kb, ownerToken, "b.txt", "text/plain", bytes)
        );
        List<String> results = List.of(
            first.get(20, TimeUnit.SECONDS).path("code").asText(),
            second.get(20, TimeUnit.SECONDS).path("code").asText()
        );
        assertThat(results).containsExactlyInAnyOrder("0", "DOC_409_DUPLICATE");
        assertThat(mapper.count(kb)).isEqualTo(1);
        assertThat(objects(kb)).hasSize(1);
        assertThat(journalCount()).isZero();
        long id = get("/knowledge-bases/" + kb + "/documents", ownerToken, 200)
            .at("/data/records/0/id")
            .asLong();
        assertThat(mapper.countTasks(id)).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=?", Long.class, id)
        ).isEqualTo(1);
        upload(otherKb, adminToken, "a.txt", "text/plain", bytes, 202);
        upload(kb, ownerToken, "a.md", "text/plain", "different".getBytes(StandardCharsets.UTF_8), 202);
        assertThat(mapper.count(kb)).isEqualTo(2);
    }

    /** 验证文档读写权限及撤销后下一请求立即拒绝。 */
    @Test
    void shouldEnforceWriteAndReadAndRevokeAllDocumentEndpointsOnNextRequest() {
        long id = uploadText(ownerToken, "acl");
        error("/knowledge-bases/" + kb + "/documents", null, 401, "AUTH_401_UNAUTHENTICATED");
        for (String suffix : List.of("", "/tasks", "/download")) {
            error("/documents/" + id + suffix, outsiderToken, 403, "KB_403_FORBIDDEN");
        }
        grant("READ");
        get("/documents/" + id + "/download", readerToken, 200);
        assertThat(
            upload(kb, readerToken, "read.txt", "text/plain", new byte[] { 65 }, 403)
                .path("code")
                .asText()
        ).isEqualTo("KB_403_FORBIDDEN");
        assertThat(
            request("/documents/" + id, HttpMethod.DELETE, readerToken, null, 403)
                .path("code")
                .asText()
        ).isEqualTo("KB_403_FORBIDDEN");
        grant("WRITE");
        uploadText(readerToken, "shared write");
        grant(null);
        for (String suffix : List.of("", "/tasks", "/download")) {
            error("/documents/" + id + suffix, readerToken, 403, "KB_403_FORBIDDEN");
        }
        error("/knowledge-bases/" + kb + "/documents", readerToken, 403, "KB_403_FORBIDDEN");
    }

    /** 验证停用或删除的知识库也拒绝管理员下载。 */
    @Test
    void shouldRejectDisabledAndDeletedKnowledgeBasesEvenForAdministratorDownload() {
        long id = uploadText(ownerToken, "disabled");
        request(
            "/knowledge-bases/" + kb,
            HttpMethod.PUT,
            ownerToken,
            Map.of("name", "disabled", "status", "DISABLED"),
            200
        );
        for (String token : List.of(ownerToken, adminToken)) {
            for (String suffix : List.of("", "/tasks", "/download")) {
                error("/documents/" + id + suffix, token, 409, "KB_409_DISABLED");
            }
            assertThat(
                upload(kb, token, "a.txt", "text/plain", new byte[] { 65 }, 409)
                    .path("code")
                    .asText()
            ).isEqualTo("KB_409_DISABLED");
        }
        request("/admin/knowledge-bases/" + kb, HttpMethod.DELETE, adminToken, null, 200);
        error("/documents/" + id + "/download", adminToken, 404, "KB_404_NOT_FOUND");
    }

    /** 验证非法 multipart 内容与大小返回可追踪错误且不写对象。 */
    @Test
    void shouldReturnTraceableErrorsForInvalidMultipartContentAndSizeWithoutWritingObjects() {
        for (String name : List.of("a.html", "a.htm", "a.exe")) {
            assertThat(
                upload(kb, ownerToken, name, "text/plain", new byte[] { 65 }, 415)
                    .path("code")
                    .asText()
            ).isEqualTo("DOC_415_UNSUPPORTED_FORMAT");
        }
        assertThat(
            upload(kb, ownerToken, "a.pdf", "application/pdf", new byte[] { 65 }, 422)
                .path("code")
                .asText()
        ).isEqualTo("DOC_422_INVALID_CONTENT");
        assertThat(
            upload(kb, ownerToken, "a.txt", "text/plain", new byte[0], 422)
                .path("code")
                .asText()
        ).isEqualTo("DOC_422_INVALID_CONTENT");
        assertThat(
            upload(kb, ownerToken, "a.txt", "text/plain", new byte[documentProperties.maxBytes() + 1], 413)
                .path("code")
                .asText()
        ).isEqualTo("DOC_413_TOO_LARGE");
        assertThat(
            upload(kb, ownerToken, "../a.txt", "text/plain", new byte[] { 65 }, 400)
                .path("code")
                .asText()
        ).isEqualTo("COMMON_400_VALIDATION");
        error("/knowledge-bases/" + kb + "/documents?pageSize=101", ownerToken, 400, "COMMON_400_VALIDATION");
        assertThat(mapper.count(kb)).isZero();
    }

    /** 验证任务插入失败时回滚数据库并补偿原文件。 */
    @Test
    void shouldRollBackAllRowsAndCompensateObjectWhenDatabaseTaskInsertFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("test-only failure")).when(mapper).insertTask(anyLong());
        assertThat(
            upload(kb, ownerToken, "failure.txt", "text/plain", new byte[] { 65 }, 503)
                .path("code")
                .asText()
        ).isEqualTo("STORAGE_503_UNAVAILABLE");
        assertThat(mapper.count(kb)).isZero();
        assertThat(objects(kb)).isEmpty();
        assertThat(journalCount()).isZero();
    }

    /** 验证补偿失败保留持久化清理记录，恢复时不删除已引用对象。 */
    @Test
    void shouldKeepDurableCleanupAfterFailedCompensationAndRecoverWithoutDeletingReferencedObjects() throws Exception {
        doThrow(new DataAccessResourceFailureException("test-only failure")).when(mapper).insertTask(anyLong());
        doThrow(
            new com.example.rag.common.exception.BusinessException(
                com.example.rag.common.exception.ErrorCode.STORAGE_503_UNAVAILABLE
            )
        )
            .when(storage)
            .remove(anyString());
        upload(kb, ownerToken, "orphan.txt", "text/plain", new byte[] { 65 }, 503);
        assertThat(mapper.count(kb)).isZero();
        assertThat(objects(kb)).hasSize(1);
        assertThat(journalCount()).isEqualTo(1);
        reset(mapper, storage);
        jdbc.update(
            "UPDATE document_storage_cleanup SET next_attempt_at=now() WHERE object_key LIKE ?",
            "kb/" + kb + "/%"
        );
        cleanup.sweep();
        assertThat(objects(kb)).isEmpty();
        assertThat(journalCount()).isZero();
        long id = uploadText(ownerToken, "referenced");
        String key = mapper.objectKey(id, 1);
        mapper.registerUpload(key, java.time.Instant.now().minusSeconds(1));
        cleanup.sweep();
        assertThat(objects(kb)).containsExactly(key);
        assertThat(journalCount()).isZero();
    }

    /** 验证上传存储后再次检查撤销权限，失败时不创建数据库记录。 */
    @Test
    void shouldRecheckRevokedWriteAfterMinioUploadAndCompensateWithoutCreatingRows() throws Exception {
        grant("WRITE");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            grant(null);
            return null;
        })
            .when(storage)
            .put(anyString(), any());
        assertThat(
            upload(kb, readerToken, "revoked.txt", "text/plain", new byte[] { 65 }, 403)
                .path("code")
                .asText()
        ).isEqualTo("KB_403_FORBIDDEN");
        assertThat(mapper.count(kb)).isZero();
        assertThat(objects(kb)).isEmpty();
        assertThat(journalCount()).isZero();
    }

    /** 验证上传清理租约变化时拒绝提交已被回收的对象。 */
    @Test
    void shouldRejectAnUploadWhoseCleanupLeaseChangedInsteadOfCommittingAnAlreadyReclaimedObject() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            jdbc.update(
                "UPDATE document_storage_cleanup SET next_attempt_at=next_attempt_at+interval '1 hour' WHERE object_key=?",
                invocation.getArgument(0, String.class)
            );
            return null;
        })
            .when(storage)
            .put(anyString(), any());
        upload(kb, ownerToken, "reclaimed.txt", "text/plain", new byte[] { 65 }, 503);
        assertThat(mapper.count(kb)).isZero();
        assertThat(objects(kb)).isEmpty();
        assertThat(journalCount()).isZero();
    }

    /** 验证上传提交等待 ACL 撤销锁并复查权限。 */
    @Test
    void shouldWaitForAclRevocationLockBeforeCommittingUpload() throws Exception {
        grant("WRITE");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (
                PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM knowledge_base WHERE id=? FOR UPDATE"
                )
            ) {
                statement.setLong(1, kb);
                statement.executeQuery().close();
            }
            CompletableFuture<JsonNode> pending = CompletableFuture.supplyAsync(() ->
                uploadAny(kb, readerToken, "lock.txt", "text/plain", new byte[] { 65 })
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
                assertThat(waiting).isTrue();
                try (
                    PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM knowledge_base_acl WHERE knowledge_base_id=?"
                    )
                ) {
                    statement.setLong(1, kb);
                    statement.executeUpdate();
                }
                connection.commit();
            } finally {
                connection.rollback();
            }
            assertThat(pending.get(20, TimeUnit.SECONDS).path("code").asText()).isEqualTo("KB_403_FORBIDDEN");
        }
        assertThat(mapper.count(kb)).isZero();
        assertThat(objects(kb)).isEmpty();
    }

    /** 验证软删除释放有效内容摘要且不覆盖或隐式创建版本。 */
    @Test
    void shouldSoftDeleteAndReleaseActiveHashWithoutOverwritingOrCreatingImplicitVersion() {
        long first = uploadText(ownerToken, "deleted");
        request("/documents/" + first, HttpMethod.DELETE, ownerToken, null, 200);
        for (String suffix : List.of("", "/tasks", "/download")) {
            error("/documents/" + first + suffix, ownerToken, 404, "DOC_404_NOT_FOUND");
        }
        long second = uploadText(ownerToken, "deleted");
        assertThat(second).isNotEqualTo(first);
        assertThat(mapper.find(second).currentVersion()).isEqualTo(1);
        error("/documents/" + second + "/download?version=9", ownerToken, 404, "DOC_404_NOT_FOUND");
        error("/documents/" + second + "/download?version=0", ownerToken, 400, "COMMON_400_VALIDATION");
    }

    /** 验证下载地址到期及版本、任务、摘要数据库约束。 */
    @Test
    void shouldExpirePreviouslyIssuedUrlAndEnforceDatabaseVersionTaskAndHashConstraints() throws Exception {
        long id = uploadText(ownerToken, "expiry");
        get("/documents/" + id + "/download", ownerToken, 200);
        URI url = URI.create(storage.download(mapper.objectKey(id, 1), "sample.txt", 30));
        assertThat(http.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(200);
        Thread.sleep(31000);
        assertThat(http.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(403);
        assertThatThrownBy(() ->
            jdbc.update("INSERT INTO document_index_task(document_id,document_version) VALUES (?,1)", id)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE document SET current_version=9 WHERE id=?", id)).isInstanceOf(
            DataIntegrityViolationException.class
        );
        assertThatThrownBy(() -> jdbc.update("UPDATE document SET source_format='HTML' WHERE id=?", id)).isInstanceOf(
            DataIntegrityViolationException.class
        );
        assertThat(
            jdbc.queryForObject("SELECT updated_at>=created_at FROM document WHERE id=?", Boolean.class, id)
        ).isTrue();
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
    private long user(String name, long departmentId, String role, String hash) {
        return jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id,role) VALUES (?,?,?,?) RETURNING id",
            Long.class,
            name,
            hash,
            departmentId,
            role
        );
    }

    /** 插入具有指定归属的测试知识库。 */
    private long knowledgeBase(String token, long dept) {
        return request(
            "/knowledge-bases",
            HttpMethod.POST,
            token,
            Map.of("name", "Phase4 test", "departmentId", dept),
            200
        )
            .at("/data/id")
            .asLong();
    }

    /** 为测试主体写入指定知识库权限。 */
    private void grant(String permission) {
        jdbc.update("DELETE FROM knowledge_base_acl WHERE knowledge_base_id=?", kb);
        if (permission != null) {
            jdbc.update(
                "INSERT INTO knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (?,'USER',?,?)",
                kb,
                Long.toString(reader),
                permission
            );
        }
    }

    /** 通过真实上传入口提交 UTF-8 测试正文。 */
    private long uploadText(String token, String text) {
        return upload(kb, token, "sample.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8), 202)
            .at("/data/documentId")
            .asLong();
    }

    /** 向真实入口提交指定格式的上传样本。 */
    private JsonNode uploadAny(long kbId, String token, String name, String mime, byte[] content) {
        return upload(kbId, token, name, mime, content, 0);
    }

    /** 提交指定文件的真实上传请求，供状态和产物断言使用。 */
    private JsonNode upload(long kbId, String token, String name, String mime, byte[] content, int expected) {
        var form = new LinkedMultiValueMap<String, Object>();
        var partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(mime));
        form.add(
            "file",
            new HttpEntity<>(
                new ByteArrayResource(content) {
                    /** 为测试 multipart 内容提供上传文件名。 */
                    @Override
                    public String getFilename() {
                        return name;
                    }
                },
                partHeaders
            )
        );
        return request("/knowledge-bases/" + kbId + "/documents", HttpMethod.POST, token, form, expected);
    }

    /** 发送带测试认证信息的 GET 请求并返回可断言响应。 */
    private JsonNode get(String path, String token, int expected) {
        return request(path, HttpMethod.GET, token, null, expected);
    }

    /** 提取或断言用例期望的公开业务错误。 */
    private void error(String path, String token, int status, String code) {
        assertThat(get(path, token, status).path("code").asText()).isEqualTo(code);
    }

    /** 构造包含 Token 和可选请求体的测试请求。 */
    private JsonNode request(String path, HttpMethod method, String token, Object body, int expected) {
        var headers = new HttpHeaders();
        if (token != null) {
            headers.set("satoken", token);
        }
        headers.setContentType(
            body instanceof LinkedMultiValueMap ? MediaType.MULTIPART_FORM_DATA : MediaType.APPLICATION_JSON
        );
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1" + path,
            method,
            new HttpEntity<>(body, headers),
            JsonNode.class
        );
        if (expected > 0) {
            assertThat(response.getStatusCode().value()).as("%s %s", method, path).isEqualTo(expected);
        }
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        if (response.getStatusCode().isError()) {
            assertThat(response.getBody().path("requestId").asText()).isEqualTo(
                response.getHeaders().getFirst("X-Request-Id")
            );
        }
        return response.getBody();
    }

    /** 列出本组测试前缀下的存储对象以验证补偿和清理。 */
    private List<String> objects(long kbId) throws Exception {
        List<String> result = new ArrayList<>();
        for (Result<Item> item : minio.listObjects(
            ListObjectsArgs.builder()
                .bucket(minioProperties.bucket())
                .prefix("kb/" + kbId + "/")
                .recursive(true)
                .build()
        )) {
            result.add(item.get().objectName());
        }
        return result;
    }

    /** 统计指定对象的持久化上传补偿记录。 */
    private long journalCount() {
        return jdbc.queryForObject(
            "SELECT count(*) FROM document_storage_cleanup WHERE object_key LIKE ?",
            Long.class,
            "kb/" + kb + "/%"
        );
    }
}
