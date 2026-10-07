package com.example.rag.document.repository.index;

import static org.assertj.core.api.Assertions.*;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.document.config.index.IndexTaskConfiguration;
import com.example.rag.document.config.index.IndexTaskProperties;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.mapper.index.IndexTaskMapper;
import com.example.rag.document.service.index.IndexStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import com.example.rag.document.worker.index.IndexTaskDispatcher;
import com.example.rag.document.worker.index.IndexTaskWorker;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "rag.seed.enabled=false", "rag.index-task.enabled=false", "rag.auth.bcrypt-strength=10" }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexTaskInfrastructureIT {

    private static final String SCHEMA = "phase5_task_" + UUID.randomUUID().toString().replace("-", "");

    /** 创建独立数据库 Schema 并迁移，避免干扰既有开发数据。 */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "55433");
        String database = System.getenv().getOrDefault("POSTGRES_DB", "rag");
        registry.add(
            "spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + database + "?currentSchema=" + SCHEMA + ",public"
        );
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Autowired
    IndexTaskStore store;

    @Autowired
    IndexTaskMapper mapper;

    @Autowired
    IndexTaskProperties properties;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Autowired
    PasswordService passwords;

    private long department;
    private long owner;
    private long reader;
    private long kb;
    private long document;
    private long taskId;
    private String ownerToken;
    private String readerToken;

    /** 创建用于断言租约事务写入的隔离产物表。 */
    @BeforeAll
    void artifactTable() {
        jdbc.execute(
            "CREATE TABLE index_test_artifact(document_id bigint, version integer, chunk_key text, content text, PRIMARY KEY(document_id,version,chunk_key))"
        );
    }

    /** 建立各用例所需的账号、知识库和文档测试数据。 */
    @BeforeEach
    void fixtures() {
        department = jdbc.queryForObject(
            "INSERT INTO sys_department(code,name) VALUES (?,?) RETURNING id",
            Long.class,
            "T_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
            "Phase5"
        );
        String hash = passwords.hash(UUID.randomUUID().toString());
        owner = user(hash);
        reader = user(hash);
        ownerToken = StpUtil.createLoginSession(owner);
        readerToken = StpUtil.createLoginSession(reader);
        kb = jdbc.queryForObject(
            "INSERT INTO knowledge_base(name,department_id,owner_user_id) VALUES ('Phase5',?,?) RETURNING id",
            Long.class,
            department,
            owner
        );
        jdbc.update(
            "INSERT INTO knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (?,'USER',?,'READ')",
            kb,
            Long.toString(reader)
        );
        document = document();
        taskId = jdbc.queryForObject("SELECT id FROM document_index_task WHERE document_id=?", Long.class, document);
    }

    /** 清理本组用例创建的数据、会话或对象，保留既有基础设施。 */
    @AfterEach
    void cleanup() {
        StpUtil.logout(owner);
        StpUtil.logout(reader);
        new TransactionTemplate(manager).executeWithoutResult(transaction -> {
            jdbc.update("DELETE FROM document_index_task");
            jdbc.update("DELETE FROM document_version");
            jdbc.update("DELETE FROM document");
            jdbc.update("DELETE FROM knowledge_base_acl");
            jdbc.update("DELETE FROM knowledge_base");
            jdbc.update("DELETE FROM sys_user");
            jdbc.update("DELETE FROM sys_department");
            jdbc.update("DELETE FROM index_test_artifact");
        });
    }

    /** 删除本组测试创建的隔离 Schema。 */
    @AfterAll
    void dropIsolatedSchema() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    /** 验证并发只领取一次，数据库约束阶段按序推进。 */
    @Test
    void shouldClaimOnceUnderConcurrencyAndEnforceOrderedStateTransitionsInDatabase() throws Exception {
        CompletableFuture<IndexTaskLease> first = CompletableFuture.supplyAsync(store::claim);
        CompletableFuture<IndexTaskLease> second = CompletableFuture.supplyAsync(store::claim);
        List<IndexTaskLease> leases = new ArrayList<>();
        IndexTaskLease one = first.get(10, TimeUnit.SECONDS);
        IndexTaskLease two = second.get(10, TimeUnit.SECONDS);
        if (one != null) {
            leases.add(one);
        }
        if (two != null) {
            leases.add(two);
        }
        assertThat(leases).hasSize(1);
        IndexTaskLease task = leases.getFirst();
        assertThat(task.attemptCount()).isEqualTo(1);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document SET status='COMPLETED' WHERE id=?", document)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> store.advance(task, IndexStage.INDEXING)).isInstanceOf(IllegalStateException.class);
        for (IndexStage stage : List.of(
            IndexStage.PARSING,
            IndexStage.CHUNKING,
            IndexStage.EMBEDDING,
            IndexStage.INDEXING
        )) {
            store.advance(task, stage);
        }
        assertThat(mapper.find(task.id()).status()).isEqualTo("COMPLETED");
        assertThat(
            jdbc.queryForObject("SELECT status FROM document_version WHERE document_id=?", String.class, document)
        ).isEqualTo("COMPLETED");
        assertThat(mapper.steps(task.id(), 100, 0))
            .hasSize(4)
            .allSatisfy(step -> {
                assertThat(step.status()).isEqualTo("COMPLETED");
                assertThat(step.finishedAt()).isAfterOrEqualTo(step.startedAt());
            });
        assertThatThrownBy(() -> store.advance(task, IndexStage.INDEXING)).isInstanceOf(LostTaskLease.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_index_task SET status='PENDING' WHERE id=?", task.id())
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 验证重启后恢复中断阶段并阻止旧租约写入。 */
    @Test
    void shouldRecoverInterruptedParsingAndModelStagesAfterRestartAndFenceOldArtifactWrites() {
        for (IndexStage stopped : List.of(IndexStage.PARSING, IndexStage.EMBEDDING)) {
            IndexTaskLease stale = store.claim();
            if (stopped == IndexStage.EMBEDDING) {
                store.advance(stale, IndexStage.PARSING);
                store.advance(stale, IndexStage.CHUNKING);
            }
            expire(stale.id());
            IndexTaskStore restarted = new IndexTaskStore(mapper, properties, manager);
            assertThat(restarted.recover()).containsExactly(stale.id());
            assertThat(mapper.find(stale.id()).status()).isEqualTo("PENDING");
            assertThat(
                jdbc.queryForObject("SELECT error_code FROM document_index_task WHERE id=?", String.class, stale.id())
            ).isEqualTo("DOC_504_TASK_TIMEOUT");
            assertThat(mapper.steps(stale.id(), 100, 0).getLast().stage()).isEqualTo(stopped.name());
            due(stale.id());
            IndexTaskLease current = restarted.claim();
            assertThat(current.leaseOwner()).isNotEqualTo(stale.leaseOwner());
            assertThatThrownBy(() -> staleContext(stale).commit(() -> writeArtifact(stale))).isInstanceOf(
                LostTaskLease.class
            );
            restarted.fail(stale, new IndexFailure("STALE", "过期执行者", false));
            assertThat(mapper.find(current.id()).status()).isEqualTo("RUNNING");
            IndexTaskContext context = staleContext(current);
            context.commit(() -> writeArtifact(current));
            context.commit(() -> writeArtifact(current));
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM index_test_artifact WHERE document_id=?",
                    Long.class,
                    current.documentId()
                )
            ).isEqualTo(1);
            new IndexTaskWorker(restarted, successProcessors()).run(current);
            assertThat(mapper.find(current.id()).status()).isEqualTo("COMPLETED");
            document = document();
        }
    }

    /** 验证自动重试使用有限退避且保留全部失败尝试。 */
    @Test
    void shouldApplyFiniteBackoffRetriesAndRetainEveryFailedAttempt() {
        for (int attempt = 0; attempt <= properties.maxAutoRetries(); attempt++) {
            IndexTaskLease task = store.claim();
            store.fail(task, new IndexFailure("MODEL_503_UNAVAILABLE", "模型暂时不可用", true));
            if (attempt < properties.maxAutoRetries()) {
                assertThat(mapper.find(task.id()).status()).isEqualTo("PENDING");
                assertThat(store.claim()).isNull();
                long delay = jdbc.queryForObject(
                    "SELECT extract(epoch FROM (next_attempt_at - finished_at))*1000 FROM document_index_task WHERE id=?",
                    Long.class,
                    task.id()
                );
                assertThat(delay).isBetween(
                    properties.backoffMillis(attempt) - 10,
                    properties.backoffMillis(attempt) + 10
                );
                due(task.id());
            }
        }
        IndexTaskLease failed = mapper.find(taskId);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.retryCount()).isEqualTo(2);
        assertThat(failed.attemptCount()).isEqualTo(3);
        assertThat(store.claim()).isNull();
        assertThat(mapper.steps(taskId, 100, 0))
            .hasSize(3)
            .allSatisfy(step -> assertThat(step.errorCode()).isEqualTo("MODEL_503_UNAVAILABLE"));
    }

    /** 验证缺少处理器明确失败，手动重试复用原任务。 */
    @Test
    void shouldExposeMissingStageAndAllowManualRetryWithoutCreatingAnotherTask() {
        new IndexTaskWorker(store, List.of()).run(store.claim());
        assertThat(mapper.find(taskId).status()).isEqualTo("FAILED");
        assertThat(mapper.find(taskId).autoRetryCount()).isZero();
        assertThat(
            request("/tasks/" + taskId + "/steps", HttpMethod.GET, ownerToken, 200)
                .at("/data/records/0/errorCode")
                .asText()
        ).isEqualTo("DOC_503_STAGE_UNAVAILABLE");
        request("/tasks/" + taskId + "/retry", HttpMethod.POST, ownerToken, 202);
        new IndexTaskWorker(store, List.of()).run(store.claim());
        assertThat(mapper.find(taskId).attemptCount()).isEqualTo(2);
        assertThat(mapper.countSteps(taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_index_task", Long.class)).isEqualTo(1);
    }

    /** 验证步骤查询授权及并发重试中的最新写权限。 */
    @Test
    void shouldAuthorizeStepsAndSerializeConcurrentManualRetriesWithFreshWritePermission() throws Exception {
        store.fail(store.claim(), new IndexFailure("DOC_422_PARSE_FAILED", "解析失败", false));
        request("/tasks/" + taskId + "/steps", HttpMethod.GET, null, 401);
        request("/tasks/" + taskId + "/steps", HttpMethod.GET, readerToken, 200);
        request("/tasks/" + taskId + "/steps?pageSize=101", HttpMethod.GET, ownerToken, 400);
        request("/tasks/999999999/steps", HttpMethod.GET, ownerToken, 404);
        request("/tasks/" + taskId + "/retry", HttpMethod.POST, readerToken, 403);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> retryStatus(ownerToken));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> retryStatus(ownerToken));
        assertThat(
            List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
        ).containsExactlyInAnyOrder(202, 409);
        assertThat(mapper.find(taskId).retryCount()).isEqualTo(1);
        jdbc.update("DELETE FROM knowledge_base_acl WHERE knowledge_base_id=?", kb);
        request("/tasks/" + taskId + "/steps", HttpMethod.GET, readerToken, 403);
        request("/tasks/" + taskId + "/retry", HttpMethod.POST, readerToken, 403);
    }

    /** 验证删除或停用来源的任务无法完成。 */
    @Test
    void shouldRefuseDeletedOrDisabledSourcesAndNeverCompleteThem() {
        IndexTaskLease task = store.claim();
        jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", kb);
        assertThatThrownBy(() -> staleContext(task).commit(() -> writeArtifact(task))).isInstanceOf(
            LostTaskLease.class
        );
        store.recover();
        assertThat(mapper.find(task.id()).status()).isEqualTo("FAILED");
        request("/tasks/" + taskId + "/retry", HttpMethod.POST, ownerToken, 409);
        jdbc.update("UPDATE knowledge_base SET status='ENABLED' WHERE id=?", kb);
        store.retry(document, taskId);
        jdbc.update("UPDATE document SET deleted=true WHERE id=?", document);
        assertThat(store.claim()).isNull();
        store.recover();
        assertThat(mapper.find(taskId).status()).isEqualTo("FAILED");
        request("/tasks/" + taskId + "/retry", HttpMethod.POST, ownerToken, 404);
    }

    /** 验证超租约产物提交回滚且拒绝跨文档任务。 */
    @Test
    void shouldRollBackArtifactWritesThatOverrunLeaseAndRejectTaskOfAnotherDocument() {
        IndexTaskLease task = store.claim();
        assertThatThrownBy(() ->
            staleContext(task).commit(() -> {
                writeArtifact(task);
                expire(task.id());
            })
        ).isInstanceOf(LostTaskLease.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM index_test_artifact", Long.class)).isZero();
        store.fail(task, new IndexFailure("DOC_422_PARSE_FAILED", "解析失败", false));
        long other = document();
        assertThatThrownBy(() -> store.retry(other, task.id())).isInstanceOf(BusinessException.class);
    }

    /** 验证 Worker 并发有界且不依赖无界内存队列。 */
    @Test
    void shouldBoundWorkerConcurrencyWithoutAnUnboundedInMemoryQueue() throws Exception {
        document();
        document();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        IndexStageProcessor blocking = new IndexStageProcessor() {
            /** 声明该测试处理器模拟的索引阶段。 */
            public IndexStage stage() {
                return IndexStage.PARSING;
            }

            /** 执行本用例定义的阶段处理或模拟阶段结果。 */
            public void process(IndexTaskContext context) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new LostTaskLease();
                }
            }
        };
        List<IndexStageProcessor> processors = new ArrayList<>(successProcessors());
        processors.removeFirst();
        processors.addFirst(blocking);
        IndexTaskProperties enabled = new IndexTaskProperties(
            true,
            2,
            Duration.ofSeconds(1),
            Duration.ofMinutes(2),
            2,
            Duration.ofSeconds(5),
            Duration.ofMinutes(1),
            20
        );
        ThreadPoolTaskExecutor executor = new IndexTaskConfiguration().documentIndexExecutor(enabled);
        executor.initialize();
        IndexTaskDispatcher dispatcher = new IndexTaskDispatcher(
            store,
            new IndexTaskWorker(store, processors),
            enabled,
            executor
        );
        try {
            dispatcher.tick();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.tick();
            assertThat(executor.getActiveCount()).isEqualTo(2);
            assertThat(executor.getQueueSize()).isZero();
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM document_index_task WHERE status='PENDING'", Long.class)
            ).isEqualTo(1);
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor.getActiveCount() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(executor.getActiveCount()).isZero();
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM document_index_task WHERE status='COMPLETED'", Long.class)
            ).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /** 构造已过期或已被替换的任务上下文。 */
    private IndexTaskContext staleContext(IndexTaskLease task) {
        return new IndexTaskContext(task, store);
    }

    /** 在任务上下文内写入测试产物以验证租约和事务边界。 */
    private void writeArtifact(IndexTaskLease task) {
        jdbc.update(
            "INSERT INTO index_test_artifact(document_id,version,chunk_key,content) VALUES (?,?,'stable-1','text') ON CONFLICT(document_id,version,chunk_key) DO UPDATE SET content=excluded.content",
            task.documentId(),
            task.documentVersion()
        );
    }

    /** 构造按顺序成功执行的测试阶段处理器。 */
    private List<IndexStageProcessor> successProcessors() {
        List<IndexStageProcessor> processors = new ArrayList<>();
        for (IndexStage stage : List.of(
            IndexStage.PARSING,
            IndexStage.CHUNKING,
            IndexStage.EMBEDDING,
            IndexStage.INDEXING
        )) {
            processors.add(
                new IndexStageProcessor() {
                    /** 声明该测试处理器模拟的索引阶段。 */
                    public IndexStage stage() {
                        return stage;
                    }

                    /** 执行本用例定义的阶段处理或模拟阶段结果。 */
                    public void process(IndexTaskContext context) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        context.commit(() -> writeArtifact(context.task()));
                    }
                }
            );
        }
        return processors;
    }

    /** 将测试任务租约设为到期以模拟旧 Worker。 */
    private void expire(long id) {
        jdbc.update(
            "UPDATE document_index_task SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
            id
        );
    }

    /** 将重试任务调整为可立即领取。 */
    private void due(long id) {
        jdbc.update(
            "UPDATE document_index_task SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",
            id
        );
    }

    /** 插入测试用户并返回生成的 ID。 */
    private long user(String hash) {
        return jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id) VALUES (?,?,?) RETURNING id",
            Long.class,
            "task." + UUID.randomUUID(),
            hash,
            department
        );
    }

    /** 插入文档与版本测试数据。 */
    private long document() {
        return new TransactionTemplate(manager).execute(transaction -> {
            long id = jdbc.queryForObject(
                "INSERT INTO document(knowledge_base_id,name,source_format,mime_type,size_bytes,sha256,object_key,created_by) VALUES (?,'task.txt','TXT','text/plain',1,?, ?,?) RETURNING id",
                Long.class,
                kb,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                "test/" + UUID.randomUUID(),
                owner
            );
            jdbc.update(
                "INSERT INTO document_version(document_id,version_no,source_format,mime_type,size_bytes,sha256,object_key) SELECT id,1,source_format,mime_type,size_bytes,sha256,object_key FROM document WHERE id=?",
                id
            );
            jdbc.update("INSERT INTO document_index_task(document_id,document_version) VALUES (?,1)", id);
            return id;
        });
    }

    /** 查询测试任务的重试状态。 */
    private int retryStatus(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("satoken", token);
        return http
            .exchange(
                "/api/v1/documents/" + document + "/tasks/" + taskId + "/retry",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                JsonNode.class
            )
            .getStatusCode()
            .value();
    }

    /** 构造包含 Token 和可选请求体的测试请求。 */
    private JsonNode request(String path, HttpMethod method, String token, int expected) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("satoken", token);
        }
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/documents/" + document + path,
            method,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertThat(response.getStatusCode().value()).as("%s %s", method, path).isEqualTo(expected);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        return response.getBody();
    }
}
