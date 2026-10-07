package com.example.rag.document.repository.chunk;

import static org.assertj.core.api.Assertions.*;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.api.PageRequest;
import com.example.rag.document.config.chunk.ChunkProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.chunk.ChunkDraft;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.dto.chunk.ChunkPreviewResponse;
import com.example.rag.document.dto.index.IndexStepResponse;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.mapper.index.IndexTaskMapper;
import com.example.rag.document.repository.index.IndexTaskStore;
import com.example.rag.document.repository.parser.ParsedDocumentRepository;
import com.example.rag.document.service.chunk.ChunkStrategy;
import com.example.rag.document.service.chunk.ChunkingStageProcessor;
import com.example.rag.document.service.chunk.StructureAwareParentChildChunkStrategy;
import com.example.rag.document.service.index.IndexTaskContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "rag.chunk.batch-size=2",
        "rag.chunk.boundary-lookback-characters=40",
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentChunkInfrastructureIT {

    private static final String SCHEMA = "phase7_" + UUID.randomUUID().toString().replace("-", "");

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
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

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
    ChunkingStageProcessor processor;

    @Autowired
    ChunkStrategy strategy;

    @Autowired
    ChunkProperties config;

    @Autowired
    PlatformTransactionManager manager;

    private long department;
    private long owner;
    private long reader;
    private long kb;
    private long document;
    private long taskId;
    private String ownerToken;
    private String readerToken;
    private final ParsedDocument sample = new ParsedDocument(
        DocumentFormat.TXT,
        List.of(
            new ParsedBlock(
                "中文制度及费用规则。\n第二行按凭证报销。".repeat(100),
                2,
                "制度",
                "费用",
                Map.of("kind", "PARAGRAPH", "lineStart", 3, "lineEnd", 103)
            )
        ),
        List.of()
    );

    /** 建立各用例所需的账号、知识库和文档测试数据。 */
    @BeforeEach
    void fixtures() {
        department = jdbc.queryForObject(
            "INSERT INTO sys_department(code,name) VALUES (?, 'Phase7') RETURNING id",
            Long.class,
            "P7_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()
        );
        String hash = passwords.hash(UUID.randomUUID().toString());
        owner = user(hash);
        reader = user(hash);
        ownerToken = StpUtil.createLoginSession(owner);
        readerToken = StpUtil.createLoginSession(reader);
        kb = jdbc.queryForObject(
            "INSERT INTO knowledge_base(name,department_id,owner_user_id) VALUES ('Phase7',?,?) RETURNING id",
            Long.class,
            department,
            owner
        );
        document = new TransactionTemplate(manager).execute(transaction -> {
            long id = jdbc.queryForObject(
                "INSERT INTO document(knowledge_base_id,name,source_format,mime_type,size_bytes,sha256,object_key,created_by) VALUES (?,'chunks.txt','TXT','text/plain',1,?,?,?) RETURNING id",
                Long.class,
                kb,
                "a".repeat(64),
                "phase7-test/" + UUID.randomUUID(),
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
    void dropSchema() {
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    /** 验证真实父子产物持久化，重试保持 ID 和数量稳定。 */
    @Test
    void shouldPersistRealParentChildArtifactsAndKeepIdsAndCountsStableOnRetry() {
        assertThat(preview("", ownerToken, 200).path("available").asBoolean()).isFalse();
        process();
        JsonNode original = preview("?pageSize=100", ownerToken, 200);
        assertThat(original.path("available").asBoolean()).isTrue();
        assertThat(original.path("strategyVersion").asText()).isEqualTo(StructureAwareParentChildChunkStrategy.VERSION);
        assertThat(original.path("boundaryLookbackCharacters").asInt()).isEqualTo(40);
        assertThat(original.path("parentCount").asInt()).isPositive();
        assertThat(original.path("childCount").asInt()).isGreaterThan(original.path("parentCount").asInt());
        assertThat(tasks.steps(taskId, 100, 0))
            .extracting(IndexStepResponse::stage)
            .containsExactly("PARSING", "CHUNKING", "EMBEDDING");
        assertThat(tasks.steps(taskId, 100, 0).get(1).status()).isEqualTo("COMPLETED");
        long count = jdbc.queryForObject("SELECT count(*) FROM document_chunk", Long.class);
        store.retry(document, taskId);
        process();
        assertThat(preview("?pageSize=100", ownerToken, 200).path("records")).isEqualTo(original.path("records"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk", Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk_result", Long.class)).isEqualTo(1);
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk c JOIN document_chunk p ON p.id=c.parent_chunk_id WHERE c.chunk_type='CHILD' AND p.chunk_type='PARENT' AND c.document_version=p.document_version AND c.knowledge_base_id=p.knowledge_base_id AND c.department_id=p.department_id",
                Long.class
            )
        ).isEqualTo(original.path("childCount").asLong());
    }

    /** 验证预览使用最新授权并校验版本、类型与分页。 */
    @Test
    void shouldAuthorizePreviewFreshAndValidateVersionTypeAndPagination() {
        process();
        preview("", null, 401);
        preview("", readerToken, 403);
        jdbc.update(
            "INSERT INTO knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (?,'USER',?,'READ')",
            kb,
            Long.toString(reader)
        );
        JsonNode child = preview("?chunkType=CHILD&page=2&pageSize=1&version=1", readerToken, 200);
        assertThat(child.path("records")).hasSize(1);
        assertThat(child.at("/records/0/chunkType").asText()).isEqualTo("CHILD");
        assertThat(child.at("/records/0/parentChunkId").asLong()).isPositive();
        assertThat(child.at("/records/0/metadata/sources/0/metadata/lineStart").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(child.toString()).doesNotContain("objectKey", "object_key", "leaseOwner");
        preview("?version=0", ownerToken, 400);
        preview("?version=2", ownerToken, 404);
        preview("?chunkType=INVALID", ownerToken, 400);
        preview("?pageSize=101", ownerToken, 400);
        preview("?page=0", ownerToken, 400);
        assertThat(preview("?chunkType=PARENT&page=999", ownerToken, 200).path("records")).isEmpty();
        jdbc.update("DELETE FROM knowledge_base_acl WHERE knowledge_base_id=?", kb);
        preview("", readerToken, 403);
        jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", kb);
        preview("", ownerToken, 409);
        jdbc.update("UPDATE knowledge_base SET status='ENABLED' WHERE id=?", kb);
        jdbc.update("UPDATE document SET deleted=true WHERE id=?", document);
        preview("", ownerToken, 404);
    }

    /** 验证较小重建隐藏旧 Chunk，再次重建恢复稳定 ID。 */
    @Test
    void shouldHideObsoleteChunksOnSmallerReplacementAndRestoreStableIdsIfRebuilt() {
        process();
        JsonNode original = preview("?pageSize=100", ownerToken, 200).path("records");
        store.retry(document, taskId);
        IndexTaskContext context = new IndexTaskContext(store.claim(), store);
        ParsedDocument smaller = new ParsedDocument(
            DocumentFormat.TXT,
            List.of(new ParsedBlock("少量正文", null, null, null, Map.of("kind", "PARAGRAPH"))),
            List.of()
        );
        context.commit(() -> chunks.replace(document, 1, strategy.split(smaller, config)));
        ChunkPreviewResponse reduced = chunks.preview(document, 1, null, new PageRequest(1, 100));
        assertThat(reduced.total()).isEqualTo(2);
        assertThat(
            jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE deleted=false", Long.class)
        ).isEqualTo(2);
        context.commit(() -> chunks.replace(document, 1, strategy.split(sample, config)));
        assertThat(preview("?pageSize=100", ownerToken, 200).path("records")).isEqualTo(original);
    }

    /** 验证提交中租约到期会回滚全部 Chunk 批次和汇总。 */
    @Test
    void shouldRollBackAllChunkBatchesAndSummaryIfTheLeaseExpiresDuringCommit() {
        process();
        JsonNode original = preview("?pageSize=100", ownerToken, 200);
        store.retry(document, taskId);
        IndexTaskLease lease = store.claim();
        List<ChunkDraft> replacement = strategy.split(
            new ParsedDocument(
                DocumentFormat.TXT,
                List.of(new ParsedBlock("new content".repeat(100), null, null, null, Map.of())),
                List.of()
            ),
            config
        );
        assertThatThrownBy(() ->
            new IndexTaskContext(lease, store).commit(() -> {
                chunks.replace(document, 1, replacement);
                jdbc.update(
                    "UPDATE document_index_task SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                    lease.id()
                );
            })
        ).isInstanceOf(LostTaskLease.class);
        assertThat(preview("?pageSize=100", ownerToken, 200)).isEqualTo(original);
        jdbc.update(
            "UPDATE document_index_task SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
            lease.id()
        );
        assertThatThrownBy(() ->
            new IndexTaskContext(lease, store).commit(() -> chunks.replace(document, 1, replacement))
        ).isInstanceOf(LostTaskLease.class);
    }

    /** 验证后续 Chunk 约束失败时回滚汇总及先前批次。 */
    @Test
    void shouldRollBackSummaryAndEarlierBatchesWhenALaterChunkViolatesAConstraint() {
        process();
        JsonNode original = preview("?pageSize=100", ownerToken, 200);
        store.retry(document, taskId);
        IndexTaskLease lease = store.claim();
        List<ChunkDraft> drafts = new java.util.ArrayList<>(strategy.split(sample, config));
        ChunkDraft last = drafts.getLast();
        drafts.set(
            drafts.size() - 1,
            new ChunkDraft(last.chunkType(), last.chunkIndex(), last.parentIndex(), " ", 1, last.sources())
        );
        assertThatThrownBy(() ->
            new IndexTaskContext(lease, store).commit(() -> chunks.replace(document, 1, drafts))
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(preview("?pageSize=100", ownerToken, 200)).isEqualTo(original);
    }

    /** 验证数据库约束父类型、版本及知识库与部门配对。 */
    @Test
    void shouldEnforceParentTypeAndVersionAndKnowledgeBaseDepartmentPairsInDatabase() {
        process();
        long parent = jdbc.queryForObject(
            "SELECT id FROM document_chunk WHERE chunk_type='PARENT' ORDER BY id LIMIT 1",
            Long.class
        );
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_chunk SET department_id=? WHERE id=?", department + 999999, parent)
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            new TransactionTemplate(manager).executeWithoutResult(transaction ->
                jdbc.update("UPDATE document_chunk SET parent_chunk_id=id WHERE chunk_type='CHILD'")
            )
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_chunk SET parent_chunk_id=NULL WHERE chunk_type='CHILD'")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 验证显式版本预览相互独立且停用来源不能写入。 */
    @Test
    void shouldSeparateExplicitVersionPreviewsAndRejectDisabledSourceWrites() {
        process();
        jdbc.update(
            "INSERT INTO document_version(document_id,version_no,source_format,mime_type,size_bytes,sha256,object_key) SELECT document_id,2,source_format,mime_type,size_bytes,sha256,object_key FROM document_version WHERE document_id=? AND version_no=1",
            document
        );
        assertThat(preview("?version=2", ownerToken, 200).path("available").asBoolean()).isFalse();
        assertThat(preview("?version=1", ownerToken, 200).path("available").asBoolean()).isTrue();
        store.retry(document, taskId);
        IndexTaskLease lease = store.claim();
        jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", kb);
        assertThatThrownBy(() -> processor.process(new IndexTaskContext(lease, store))).isInstanceOf(
            LostTaskLease.class
        );
    }

    /** 验证新解析后旧 Chunk 暂不可见，缺少解析时不能切分。 */
    @Test
    void shouldHideChunksFromAnOlderParseUntilChunkingCommitsAndRejectMissingParse() {
        IndexTaskLease lease = store.claim();
        IndexTaskContext context = new IndexTaskContext(lease, store);
        assertThatThrownBy(() -> processor.process(context))
            .isInstanceOf(IndexFailure.class)
            .extracting("code")
            .isEqualTo("DOC_409_PARSE_REQUIRED");
        context.commit(() -> parsed.replace(document, 1, sample));
        processor.process(context);
        assertThat(preview("", ownerToken, 200).path("available").asBoolean()).isTrue();
        context.commit(() -> parsed.replace(document, 1, sample));
        assertThat(preview("", ownerToken, 200).path("available").asBoolean()).isFalse();
        processor.process(context);
        assertThat(preview("", ownerToken, 200).path("available").asBoolean()).isTrue();
    }

    /** 验证历史对齐配置不被覆盖，新列受约束校验。 */
    @Test
    void shouldPreserveHistoricalAlignmentConfigurationAndValidateTheNewColumn() {
        process();
        jdbc.update(
            "UPDATE document_chunk_result SET strategy_version='structure-parent-child-v1', boundary_lookback_characters=NULL"
        );
        JsonNode historical = preview("", ownerToken, 200);
        assertThat(historical.path("strategyVersion").asText()).isEqualTo("structure-parent-child-v1");
        assertThat(historical.path("boundaryLookbackCharacters").isNull()).isTrue();
        assertThatThrownBy(() ->
            jdbc.update("UPDATE document_chunk_result SET boundary_lookback_characters=1025")
        ).isInstanceOf(DataIntegrityViolationException.class);
        store.retry(document, taskId);
        process();
        assertThat(preview("", ownerToken, 200).path("boundaryLookbackCharacters").asInt()).isEqualTo(40);
    }

    /** 执行本用例定义的阶段处理或模拟阶段结果。 */
    private void process() {
        IndexTaskLease lease = store.claim();
        IndexTaskContext context = new IndexTaskContext(lease, store);
        context.commit(() -> parsed.replace(document, 1, sample));
        store.advance(lease, IndexStage.PARSING);
        processor.process(context);
        store.advance(lease, IndexStage.CHUNKING);
        store.fail(lease, new IndexFailure("DOC_503_STAGE_UNAVAILABLE", "Phase8 embedding pending", false));
    }

    /** 插入测试用户并返回生成的 ID。 */
    private long user(String hash) {
        return jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id) VALUES (?,?,?) RETURNING id",
            Long.class,
            "chunks." + UUID.randomUUID(),
            hash,
            department
        );
    }

    /** 请求指定版本的授权预览并返回可断言内容。 */
    private JsonNode preview(String query, String token, int expected) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("satoken", token);
        }
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/documents/" + document + "/chunks" + query,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(expected);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        return expected < 400 ? response.getBody().path("data") : response.getBody();
    }
}
