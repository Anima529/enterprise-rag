package com.example.rag.document.repository.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.security.AuthorizedScopeFixtures;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.dto.embedding.EmbeddingPreviewResponse;
import com.example.rag.document.dto.index.IndexStepResponse;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.mapper.index.IndexTaskMapper;
import com.example.rag.document.repository.chunk.DocumentChunkRepository;
import com.example.rag.document.repository.index.IndexTaskStore;
import com.example.rag.document.repository.parser.ParsedDocumentRepository;
import com.example.rag.document.service.chunk.ChunkingStageProcessor;
import com.example.rag.document.service.embedding.EmbeddingStageProcessor;
import com.example.rag.document.service.embedding.VectorIndexingStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import com.example.rag.knowledgebase.initializer.KnowledgeBaseModelInitializer;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.rag.model.support.EmbeddingVectors;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "rag.seed.enabled=false",
        "rag.index-task.enabled=false",
        "rag.auth.bcrypt-strength=10",
        "rag.chunk.child-tokens=32",
        "rag.chunk.parent-tokens=128",
        "rag.chunk.overlap-tokens=5",
        "rag.embedding.batch-size=2",
        "rag.embedding.persistence-batch-size=2",
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentEmbeddingInfrastructureIT {

    private static final String SCHEMA = "phase8_" + UUID.randomUUID().toString().replace("-", "");

    /** 为本组集成测试建立隔离数据库并执行迁移。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "55433");
        String db = System.getenv().getOrDefault("POSTGRES_DB", "rag");
        registry.add(
            "spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?currentSchema=" + SCHEMA + ",public"
        );
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    PasswordService passwords;

    @Autowired
    IndexTaskStore store;

    @Autowired
    IndexTaskMapper tasks;

    @Autowired
    ParsedDocumentRepository parsed;

    @Autowired
    DocumentChunkRepository chunks;

    @Autowired
    ChunkingStageProcessor chunking;

    @Autowired
    DocumentEmbeddingRepository embeddings;

    @Autowired
    EmbeddingStageProcessor embedding;

    @Autowired
    VectorIndexingStageProcessor indexing;

    @Autowired
    VectorSearchRepository search;

    @Autowired
    TestRestTemplate http;

    @Autowired
    EmbeddingProperties embeddingConfig;

    @Autowired
    ChatProperties chatConfig;

    @Autowired
    KnowledgeBaseModelInitializer modelInitializer;

    @MockitoBean
    EmbeddingGateway gateway;

    private long department;
    private long owner;
    private long reader;
    private long kb;
    private long document;
    private long taskId;
    private String ownerToken;
    private String readerToken;
    private IndexTaskLease lease;
    private final ParsedDocument sample = new ParsedDocument(
        DocumentFormat.TXT,
        List.of(
            new ParsedBlock(
                "中文制度及报销规则。提交凭证后在五个工作日审批。".repeat(25),
                2,
                "制度",
                "费用",
                Map.of("lineStart", 3)
            )
        ),
        List.of()
    );

    /** 为隔离数据库启用测试所需的 PGVector 扩展。 */
    private static List<Float> vector() {
        List<Float> values = new ArrayList<>(Collections.nCopies(1024, 0f));
        values.set(0, 1f);
        return List.copyOf(values);
    }

    /** 建立各用例所需的账号、知识库和文档测试数据。 */
    @BeforeEach
    void fixtures() {
        department = jdbc.queryForObject(
            "INSERT INTO sys_department(code,name) VALUES (?,'Phase8') RETURNING id",
            Long.class,
            "P8_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()
        );
        String hash = passwords.hash(UUID.randomUUID().toString());
        owner = user(hash, department);
        reader = user(hash, department);
        ownerToken = StpUtil.createLoginSession(owner);
        readerToken = StpUtil.createLoginSession(reader);
        kb = jdbc.queryForObject(
            "INSERT INTO knowledge_base(name,department_id,owner_user_id) VALUES ('Phase8',?,?) RETURNING id",
            Long.class,
            department,
            owner
        );
        document = new TransactionTemplate(manager).execute(transaction -> {
            long id = jdbc.queryForObject(
                "INSERT INTO document(knowledge_base_id,name,source_format,mime_type,size_bytes,sha256,object_key,created_by) VALUES (?,'vectors.txt','TXT','text/plain',1,?,?,?) RETURNING id",
                Long.class,
                kb,
                "a".repeat(64),
                "phase8-test/" + UUID.randomUUID(),
                owner
            );
            jdbc.update(
                "INSERT INTO document_version(document_id,version_no,source_format,mime_type,size_bytes,sha256,object_key) SELECT id,1,source_format,mime_type,size_bytes,sha256,object_key FROM document WHERE id=?",
                id
            );
            jdbc.update("INSERT INTO document_index_task(document_id,document_version) VALUES (?,1)", id);
            return id;
        });
        taskId = jdbc.queryForObject("SELECT id FROM document_index_task WHERE document_id=?", Long.class, document);
        when(gateway.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            assertThat(
                org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            ).isFalse();
            return new EmbeddingBatch(
                texts
                    .stream()
                    .map(text -> vector())
                    .toList(),
                7,
                1
            );
        });
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
        });
    }

    /** 删除本组测试创建的隔离 Schema。 */
    @AfterAll
    void drop() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    /** 验证只有完整版本可完成，并返回授权汇总与范围内余弦候选。 */
    @Test
    void shouldCompleteOnlyWholeVersionAndExposeAuthorizedSummaryAndScopedCosineHits() {
        complete();
        EmbeddingPreviewResponse result = embeddings.preview(document, 1);
        assertThat(result.available()).isTrue();
        assertThat(result.indexed()).isTrue();
        assertThat(result.model()).isEqualTo(embeddingConfig.model());
        assertThat(result.dimensions()).isEqualTo(1024);
        assertThat(result.callCount()).isEqualTo(result.batchCount());
        assertThat(result.totalTokens()).isEqualTo(result.batchCount() * 7L);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL", Integer.class)
        ).isEqualTo(result.childCount());
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE chunk_type='PARENT' AND embedding IS NOT NULL",
                Integer.class
            )
        ).isZero();
        assertThat(tasks.steps(taskId, 100, 0))
            .extracting(IndexStepResponse::status)
            .containsOnly("COMPLETED");
        assertThat(search.search(scope(owner), vector(), 100))
            .hasSize(result.childCount())
            .allSatisfy(hit -> {
                assertThat(hit.knowledgeBaseId()).isEqualTo(kb);
                assertThat(hit.departmentId()).isEqualTo(department);
                assertThat(hit.documentVersion()).isEqualTo(1);
                assertThat(hit.distance()).isCloseTo(0, within(0.00001));
                assertThat(hit.metadataJson()).contains("lineStart");
            });
        assertThat(preview(ownerToken, 200).path("indexed").asBoolean()).isTrue();
        preview(readerToken, 403);
        preview(null, 401);
    }

    /** 验证重试保持稳定 ID 和单个子块向量，重新切分使旧向量汇总失效。 */
    @Test
    void shouldKeepStableIdsAndSingleVectorPerChildOnRetryAndInvalidateOldEmbeddingOnRechunk() {
        chunk();
        embedding.process(context());
        store.advance(lease, IndexStage.EMBEDDING);
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM document_chunk WHERE embedding IS NOT NULL ORDER BY id",
            Long.class
        );
        store.fail(
            lease,
            new IndexFailure("DOC_500_TEST_INTERRUPTED", "Test final acknowledgement interrupted", false)
        );
        store.retry(document, taskId);
        chunk();
        assertThat(embeddings.preview(document, 1).available()).isFalse();
        assertThat(search.search(scope(owner), vector(), 100)).isEmpty();
        finish();
        assertThat(
            jdbc.queryForList("SELECT id FROM document_chunk WHERE embedding IS NOT NULL ORDER BY id", Long.class)
        ).isEqualTo(ids);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_embedding_result", Integer.class)).isEqualTo(1);
    }

    /** 验证后续批次不完整时不保存任何向量且允许任务重试。 */
    @Test
    void shouldSaveNoVectorsWhenALaterBatchIsPartialAndAllowTaskRetry() {
        chunk();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(gateway.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return new EmbeddingBatch(
                calls.incrementAndGet() == 1
                    ? texts
                          .stream()
                          .map(t -> vector())
                          .toList()
                    : List.of(),
                7,
                1
            );
        });
        assertThatThrownBy(() -> embedding.process(context()))
            .isInstanceOf(IndexFailure.class)
            .extracting("retryable")
            .isEqualTo(true);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL", Integer.class)
        ).isZero();
        assertThat(embeddings.preview(document, 1).available()).isFalse();
        store.fail(lease, new IndexFailure("MODEL_502_INVALID_BATCH", "partial", true));
        assertThat(tasks.find(taskId).status()).isEqualTo("PENDING");
        assertThat(tasks.find(taskId).autoRetryCount()).isEqualTo(1);
    }

    /** 验证错误维度和零向量不能完成索引。 */
    @Test
    void shouldRejectWrongDimensionAndZeroVectorWithoutCompleting() {
        chunk();
        for (List<Float> invalid : List.of(List.of(1f), Collections.nCopies(1024, 0f))) {
            when(gateway.embedBatch(anyList())).thenAnswer(invocation -> {
                List<String> texts = invocation.getArgument(0);
                return new EmbeddingBatch(
                    texts
                        .stream()
                        .map(t -> invalid)
                        .toList(),
                    0,
                    1
                );
            });
            assertThatThrownBy(() -> embedding.process(context()))
                .isInstanceOf(IndexFailure.class)
                .extracting("code")
                .isEqualTo("MODEL_422_DIMENSION_MISMATCH");
        }
        assertThat(tasks.find(taskId).stage()).isEqualTo(IndexStage.EMBEDDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_embedding_result", Integer.class)).isZero();
    }

    /** 验证提交中租约到期回滚全部批次与汇总，并阻止旧 Worker。 */
    @Test
    void shouldRollBackAllBatchesAndSummaryOnLateLeaseExpiryAndFenceOldWorkers() {
        chunk();
        DocumentEmbeddingRepository.Snapshot snapshot = embeddings.load(document, 1);
        List<List<Float>> vectors = snapshot
            .children()
            .stream()
            .map(c -> vector())
            .toList();
        assertThatThrownBy(() ->
            context().commit(() -> {
                embeddings.replace(document, 1, snapshot, vectors, 1, 1, 0, 0);
                jdbc.update(
                    "UPDATE document_index_task SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                    taskId
                );
            })
        ).isInstanceOf(LostTaskLease.class);
        assertThat(embeddings.preview(document, 1).available()).isFalse();
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL", Integer.class)
        ).isZero();
        jdbc.update(
            "UPDATE document_index_task SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
            taskId
        );
        assertThatThrownBy(() ->
            context().commit(() -> embeddings.replace(document, 1, snapshot, vectors, 1, 1, 0, 0))
        ).isInstanceOf(LostTaskLease.class);
    }

    /** 验证 Chunk 快照变化或缺失向量时拒绝索引完成。 */
    @Test
    void shouldRejectAChangedChunkSnapshotOrMissingVectorBeforeIndexCompletion() {
        chunk();
        DocumentEmbeddingRepository.Snapshot snapshot = embeddings.load(document, 1);
        jdbc.update("UPDATE document_chunk SET content=content||' changed' WHERE chunk_type='CHILD'");
        assertThatThrownBy(() ->
            context().commit(() ->
                embeddings.replace(
                    document,
                    1,
                    snapshot,
                    snapshot
                        .children()
                        .stream()
                        .map(c -> vector())
                        .toList(),
                    1,
                    1,
                    0,
                    0
                )
            )
        ).isInstanceOf(IndexFailure.class);
        embedding.process(context());
        store.advance(lease, IndexStage.EMBEDDING);
        jdbc.update(
            "UPDATE document_chunk SET embedding=NULL,embedding_model=NULL,embedding_dimensions=NULL,embedding_content_hash=NULL WHERE id=(SELECT min(id) FROM document_chunk WHERE chunk_type='CHILD')"
        );
        assertThatThrownBy(() -> indexing.process(context())).isInstanceOf(IndexFailure.class);
        assertThat(embeddings.preview(document, 1).available()).isFalse();
        assertThat(tasks.find(taskId).stage()).isEqualTo(IndexStage.INDEXING);
    }

    /** 验证候选 SQL 重新校验 ACL、来源状态和版本。 */
    @Test
    void shouldRecheckRevokedAclAndDisabledDeletedAndOldVersionsInCandidateSql() {
        complete();
        RetrievalScope stale = scope(reader);
        assertThat(search.search(stale, vector(), 100)).isEmpty();
        jdbc.update(
            "INSERT INTO knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (?,'USER',?,'READ')",
            kb,
            Long.toString(reader)
        );
        assertThat(search.search(stale, vector(), 100)).isNotEmpty();
        preview(readerToken, 200);
        jdbc.update("DELETE FROM knowledge_base_acl");
        assertThat(search.search(stale, vector(), 100)).isEmpty();
        preview(readerToken, 403);
        jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", kb);
        assertThat(search.search(scope(owner), vector(), 100)).isEmpty();
        preview(ownerToken, 409);
        jdbc.update("UPDATE knowledge_base SET status='ENABLED' WHERE id=?", kb);
        jdbc.update("UPDATE document SET deleted=true WHERE id=?", document);
        assertThat(search.search(scope(owner), vector(), 100)).isEmpty();
        preview(ownerToken, 404);
        jdbc.update("UPDATE document SET deleted=false WHERE id=?", document);
        jdbc.update(
            "INSERT INTO document_version(document_id,version_no,source_format,mime_type,size_bytes,sha256,object_key) SELECT document_id,2,source_format,mime_type,size_bytes,sha256,object_key FROM document_version WHERE document_id=?",
            document
        );
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document SET current_version=2 WHERE id=?", document)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(search.search(scope(owner), vector(), 100)).isNotEmpty();
    }

    /** 验证显式跨部门分享，拒绝伪造配对、失效账号和损坏内容。 */
    @Test
    void shouldAllowExplicitCrossDepartmentSharingAndRejectForgedPairsInactiveUsersAndCorruptContent() {
        complete();
        long otherDept = jdbc.queryForObject(
            "INSERT INTO sys_department(code,name) VALUES ('CROSS','Cross') RETURNING id",
            Long.class
        );
        jdbc.update("UPDATE sys_user SET department_id=? WHERE id=?", otherDept, reader);
        jdbc.update(
            "INSERT INTO knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (?,'USER',?,'READ')",
            kb,
            Long.toString(reader)
        );
        assertThat(search.search(scope(reader), vector(), 100)).isNotEmpty();
        assertThat(search.search(AuthorizedScopeFixtures.issue(reader, kb, otherDept), vector(), 100)).isEmpty();
        jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", reader);
        assertThat(search.search(scope(reader), vector(), 100)).isEmpty();
        jdbc.update("UPDATE sys_user SET status='ACTIVE' WHERE id=?", reader);
        jdbc.update("UPDATE document_chunk SET content='corrupt' WHERE chunk_type='CHILD'");
        assertThat(search.search(scope(reader), vector(), 100)).isEmpty();
    }

    /** 验证数据库约束非空模型、父块及向量维度。 */
    @Test
    void shouldEnforceNonblankModelParentAndVectorDimensionsInDatabase() {
        complete();
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_chunk SET embedding_model=' ' WHERE chunk_type='CHILD'")
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_chunk SET embedding_model=NULL WHERE chunk_type='CHILD'")
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_chunk SET embedding_dimensions=NULL WHERE chunk_type='CHILD'")
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE document_embedding_result SET model=' '")).isInstanceOf(
            DataIntegrityViolationException.class
        );
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_chunk SET embedding=CAST('[1,2]' AS vector) WHERE chunk_type='CHILD'")
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update(
                "UPDATE document_chunk SET embedding=CAST(? AS vector),embedding_model='text-embedding-v3',embedding_dimensions=1024,embedding_content_hash=? WHERE chunk_type='PARENT'",
                EmbeddingVectors.literal(vector(), 1024),
                "a".repeat(64)
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> search.search(null, vector(), 5)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 验证模型配置同步保留旧向量标记，检索排除其他模型空间。 */
    @Test
    void shouldPreserveLegacyVectorsWhenSynchronizingConfigurationAndExcludeOtherModelSpaces() {
        complete();
        List<Long> ids = jdbc.queryForList(
            "SELECT id FROM document_chunk WHERE embedding IS NOT NULL ORDER BY id",
            Long.class
        );
        // 维度和值相同，也可能属于不同的模型向量空间。
        String legacyModel = "legacy-embedding-model";
        jdbc.update("UPDATE document_chunk SET embedding_model=? WHERE chunk_type='CHILD'", legacyModel);
        jdbc.update("UPDATE document_embedding_result SET model=?", legacyModel);
        modelInitializer.run(null);
        assertThat(
            jdbc.queryForObject("SELECT embedding_model FROM knowledge_base WHERE id=?", String.class, kb)
        ).isEqualTo(embeddingConfig.model());
        assertThat(
            jdbc.queryForObject("SELECT generation_model FROM knowledge_base WHERE id=?", String.class, kb)
        ).isEqualTo(chatConfig.model());
        assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT embedding_model FROM document_chunk WHERE embedding IS NOT NULL",
                String.class
            )
        ).containsExactly(legacyModel);
        assertThat(embeddings.preview(document, 1).model()).isEqualTo(legacyModel);
        assertThat(search.search(scope(owner), vector(), 100)).isEmpty();
        assertThatThrownBy(() -> embeddings.verify(document, 1)).isInstanceOf(IndexFailure.class);
        // 重建索引使用当前网关替换向量，保持稳定分块 ID 不变。
        DocumentEmbeddingRepository.Snapshot snapshot = embeddings.load(document, 1);
        new TransactionTemplate(manager).executeWithoutResult(transaction ->
            embeddings.replace(
                document,
                1,
                snapshot,
                snapshot
                    .children()
                    .stream()
                    .map(child -> vector())
                    .toList(),
                1,
                1,
                0,
                0
            )
        );
        assertThat(
            jdbc.queryForList("SELECT id FROM document_chunk WHERE embedding IS NOT NULL ORDER BY id", Long.class)
        ).isEqualTo(ids);
        assertThat(embeddings.preview(document, 1).model()).isEqualTo(embeddingConfig.model());
        assertThat(search.search(scope(owner), vector(), 100)).hasSize(ids.size());
        jdbc.update("UPDATE document_embedding_result SET model=?", legacyModel);
        assertThat(search.search(scope(owner), vector(), 100)).isEmpty();
        assertThat(embeddings.preview(document, 1).available()).isFalse();
    }

    /** 验证新解析隐藏旧向量，模型调用期间停用来源不能提交。 */
    @Test
    void shouldHideEmbeddingsAfterANewerParseAndRejectDisabledSourceDuringModelCall() {
        chunk();
        embedding.process(context());
        store.advance(lease, IndexStage.EMBEDDING);
        store.fail(
            lease,
            new IndexFailure("DOC_500_TEST_INTERRUPTED", "Test final acknowledgement interrupted", false)
        );
        store.retry(document, taskId);
        lease = store.claim();
        context().commit(() -> parsed.replace(document, 1, sample));
        assertThat(embeddings.preview(document, 1).available()).isFalse();
        assertThat(search.search(scope(owner), vector(), 100)).isEmpty();
        chunking.process(context());
        store.advance(lease, IndexStage.PARSING);
        store.advance(lease, IndexStage.CHUNKING);
        when(gateway.embedBatch(anyList())).thenAnswer(invocation -> {
            jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", kb);
            List<String> texts = invocation.getArgument(0);
            return new EmbeddingBatch(
                texts
                    .stream()
                    .map(t -> vector())
                    .toList(),
                0,
                1
            );
        });
        assertThatThrownBy(() -> embedding.process(context())).isInstanceOf(LostTaskLease.class);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL", Integer.class)
        ).isZero();
    }

    /** 插入测试用户并返回生成的 ID。 */
    private long user(String hash, long dept) {
        return jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id) VALUES (?,?,?) RETURNING id",
            Long.class,
            "vectors." + UUID.randomUUID(),
            hash,
            dept
        );
    }

    /** 构造或查询用例所需的授权检索范围。 */
    private RetrievalScope scope(long actor) {
        return AuthorizedScopeFixtures.issue(actor, kb, department);
    }

    /** 构造受任务租约约束的阶段上下文。 */
    private IndexTaskContext context() {
        return new IndexTaskContext(lease, store);
    }

    /** 插入用例所需的父子 Chunk 与来源信息。 */
    private void chunk() {
        lease = store.claim();
        context().commit(() -> parsed.replace(document, 1, sample));
        store.advance(lease, IndexStage.PARSING);
        chunking.process(context());
        store.advance(lease, IndexStage.CHUNKING);
    }

    /** 推进测试任务到目标阶段并提交对应产物。 */
    private void finish() {
        embedding.process(context());
        store.advance(lease, IndexStage.EMBEDDING);
        indexing.process(context());
        store.advance(lease, IndexStage.INDEXING);
    }

    /** 执行测试版本的后续阶段直到索引完成。 */
    private void complete() {
        chunk();
        finish();
    }

    /** 请求指定版本的授权预览并返回可断言内容。 */
    private JsonNode preview(String token, int status) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("satoken", token);
        }
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/documents/" + document + "/embedding",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().toString()).doesNotContain("embedding_content_hash", "objectKey", "test-secret");
        return status == 200 ? response.getBody().path("data") : response.getBody();
    }
}
