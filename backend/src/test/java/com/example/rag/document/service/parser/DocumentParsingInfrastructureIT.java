package com.example.rag.document.service.parser;

import static org.assertj.core.api.Assertions.*;

import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.config.MinioProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.domain.parser.ParsedBlock;
import com.example.rag.document.domain.parser.ParsedDocument;
import com.example.rag.document.dto.index.IndexStepResponse;
import com.example.rag.document.dto.parser.ParsePreviewResponse;
import com.example.rag.document.dto.parser.ParsedBlockResponse;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.mapper.index.IndexTaskMapper;
import com.example.rag.document.repository.index.IndexTaskStore;
import com.example.rag.document.repository.parser.ParsedDocumentRepository;
import com.example.rag.document.service.index.IndexTaskContext;
import com.example.rag.document.support.DocumentFixtures;
import com.example.rag.document.support.LegacyDocFixtures;
import com.example.rag.document.worker.index.IndexTaskWorker;
import com.fasterxml.jackson.databind.JsonNode;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "rag.seed.enabled=false",
        "rag.index-task.enabled=false",
        "rag.auth.bcrypt-strength=10",
        "rag.parser-persistence.batch-size=2",
        "rag.embedding.api-key=",
        "rag.embedding.dashscope-api-key=",
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentParsingInfrastructureIT {

    private static final String SCHEMA = "phase6_parse_" + UUID.randomUUID().toString().replace("-", "");

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
    IndexTaskWorker worker;

    @Autowired
    IndexTaskMapper tasks;

    @Autowired
    ParsedDocumentRepository repository;

    @Autowired
    MinioClient minio;

    @Autowired
    MinioProperties minioProperties;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager manager;

    private long department;
    private long owner;
    private long reader;
    private long kb;
    private String ownerToken;
    private String readerToken;

    /** 建立各用例所需的账号、知识库和文档测试数据。 */
    @BeforeEach
    void fixtures() {
        department = jdbc.queryForObject(
            "INSERT INTO sys_department(code,name) VALUES (?, 'Phase6') RETURNING id",
            Long.class,
            "P6_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()
        );
        String hash = passwords.hash(UUID.randomUUID().toString());
        owner = user(hash);
        reader = user(hash);
        ownerToken = StpUtil.createLoginSession(owner);
        readerToken = StpUtil.createLoginSession(reader);
        kb = jdbc.queryForObject(
            "INSERT INTO knowledge_base(name,department_id,owner_user_id) VALUES ('Phase6',?,?) RETURNING id",
            Long.class,
            department,
            owner
        );
    }

    /** 清理本组用例创建的数据、会话或对象，保留既有基础设施。 */
    @AfterEach
    void cleanup() throws Exception {
        for (String key : jdbc.queryForList("SELECT object_key FROM document_version", String.class)) {
            minio.removeObject(RemoveObjectArgs.builder().bucket(minioProperties.bucket()).object(key).build());
        }
        StpUtil.logout(owner);
        StpUtil.logout(reader);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(transaction -> {
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

    /** 验证真实存储的支持格式可解析，缺少向量密钥时明确失败。 */
    @Test
    void shouldParseRealStoredSupportedFormatsAndReportMissingEmbeddingCredentials() throws Exception {
        for (DocumentFormat format : DocumentFormat.values()) {
            long id = upload(format, DocumentFixtures.content(format));
            assertThat(preview(id, "", ownerToken, 200).path("available").asBoolean()).isFalse();
            IndexTaskLease task = store.claim();
            worker.run(task);
            assertThat(tasks.find(task.id()).status()).isEqualTo("FAILED");
            assertThat(tasks.steps(task.id(), 100, 0))
                .extracting(IndexStepResponse::stage)
                .containsExactly("PARSING", "CHUNKING", "EMBEDDING");
            assertThat(tasks.steps(task.id(), 100, 0).getFirst().status()).isEqualTo("COMPLETED");
            assertThat(tasks.steps(task.id(), 100, 0).getLast().errorCode()).isEqualTo("MODEL_503_NOT_CONFIGURED");
            JsonNode parsed = preview(id, "", ownerToken, 200);
            assertThat(parsed.path("available").asBoolean()).isTrue();
            assertThat(parsed.path("total").asInt()).isPositive();
            assertThat(parsed.path("records").get(0).path("text").asText()).contains(
                format == DocumentFormat.PDF ? "Phase 4" : "中文"
            );
            assertThat(parsed.toString()).doesNotContain("objectKey", "object_key");
        }
    }

    /** 验证同版本重试替换产物，过期租约不能写入真实解析结果。 */
    @Test
    void shouldReplaceSameVersionOnRetryAndRejectExpiredActualParsedArtifactWrites() throws Exception {
        long id = upload(DocumentFormat.MD, DocumentFixtures.content(DocumentFormat.MD));
        IndexTaskLease first = store.claim();
        worker.run(first);
        JsonNode original = preview(id, "", ownerToken, 200);
        store.retry(id, first.id());
        IndexTaskLease next = store.claim();
        worker.run(next);
        assertThat(preview(id, "", ownerToken, 200).path("records")).isEqualTo(original.path("records"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parse_result", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parsed_block", Long.class)).isEqualTo(
            original.path("total").asLong()
        );
        store.retry(id, first.id());
        IndexTaskLease lease = store.claim();
        ParsedDocument replacement = new ParsedDocument(
            DocumentFormat.MD,
            List.of(new ParsedBlock("should roll back", null, null, null, Map.of("kind", "PARAGRAPH"))),
            List.of()
        );
        assertThatThrownBy(() ->
            new IndexTaskContext(lease, store).commit(() -> {
                repository.replace(id, 1, replacement);
                jdbc.update(
                    "UPDATE document_index_task SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
                    lease.id()
                );
            })
        ).isInstanceOf(LostTaskLease.class);
        assertThat(preview(id, "", ownerToken, 200).path("records")).isEqualTo(original.path("records"));
        jdbc.update(
            "UPDATE document_index_task SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",
            lease.id()
        );
        assertThatThrownBy(() ->
            new IndexTaskContext(lease, store).commit(() -> repository.replace(id, 1, replacement))
        ).isInstanceOf(LostTaskLease.class);
    }

    /** 验证预览复查 ACL 并校验版本、分页及来源状态。 */
    @Test
    void shouldCheckFreshAclVersionsPaginationDeletionAndDisabledKnowledgeBaseForPreview() throws Exception {
        long id = upload(DocumentFormat.TXT, "一\n\n二\n\n三".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        worker.run(store.claim());
        preview(id, "", null, 401);
        preview(id, "", readerToken, 403);
        jdbc.update(
            "INSERT INTO knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (?,'USER',?,'READ')",
            kb,
            Long.toString(reader)
        );
        JsonNode second = preview(id, "?version=1&page=2&pageSize=1", readerToken, 200);
        assertThat(second.path("records").get(0).path("text").asText()).isEqualTo("二");
        assertThat(second.path("records").get(0).path("blockIndex").asInt()).isEqualTo(1);
        preview(id, "?version=0", ownerToken, 400);
        preview(id, "?version=2", ownerToken, 404);
        preview(id, "?pageSize=101", ownerToken, 400);
        preview(id, "?page=0", ownerToken, 400);
        assertThat(preview(id, "?page=99", readerToken, 200).path("records")).isEmpty();
        jdbc.update("DELETE FROM knowledge_base_acl WHERE knowledge_base_id=?", kb);
        preview(id, "", readerToken, 403);
        jdbc.update("UPDATE knowledge_base SET status='DISABLED' WHERE id=?", kb);
        preview(id, "", ownerToken, 409);
        jdbc.update("UPDATE knowledge_base SET status='ENABLED' WHERE id=?", kb);
        jdbc.update("UPDATE document SET deleted=true WHERE id=?", id);
        preview(id, "", ownerToken, 404);
    }

    /** 验证 JSON 和可空位置完整往返，较小重建移除旧块。 */
    @Test
    void shouldRoundTripJsonAndNullablePositionsAndRemoveOldBlocksOnSmallerReplacement() throws Exception {
        long id = upload(DocumentFormat.MD, DocumentFixtures.content(DocumentFormat.MD));
        IndexTaskLease first = store.claim();
        worker.run(first);
        store.retry(id, first.id());
        IndexTaskLease lease = store.claim();
        Map<String, Object> metadata = Map.of(
            "kind",
            "PARAGRAPH",
            "labels",
            List.of("中文", "O'Reilly", "\"引用\""),
            "details",
            Map.of("lineStart", 7, "closed", false)
        );
        List<ParsedBlock> blocks = List.of(
            new ParsedBlock("中文 O'Reilly\n<script>原样保存</script>", null, null, null, metadata),
            new ParsedBlock("第二块", 2, "章节\"一\"", "小节\\二", Map.of()),
            new ParsedBlock("第三块", null, "章节三", null, Map.of("rowStart", 9))
        );
        List<String> warnings = List.of("提示\"一\"", "提示\\二");
        ParsedDocument replacement = new ParsedDocument(DocumentFormat.MD, blocks, warnings);
        IndexTaskContext context = new IndexTaskContext(lease, store);
        context.commit(() -> repository.replace(id, 1, replacement));
        ParsePreviewResponse saved = repository.preview(id, 1, new PageRequest(1, 20));
        assertThat(saved.format()).isEqualTo(DocumentFormat.MD);
        assertThat(saved.parsedAt()).isNotNull();
        assertThat(saved.parserVersion()).isEqualTo("phase6-v5-source-structure");
        assertThat(saved.warnings()).containsExactlyElementsOf(warnings);
        assertThat(saved.characterCount()).isEqualTo(
            blocks
                .stream()
                .mapToInt(block -> block.text().length())
                .sum()
        );
        assertThat(saved.total()).isEqualTo(3);
        assertThat(saved.records()).extracting(ParsedBlockResponse::blockIndex).containsExactly(0, 1, 2);
        assertThat(saved.records())
            .extracting(ParsedBlockResponse::text)
            .containsExactly(blocks.get(0).text(), "第二块", "第三块");
        assertThat(saved.records().getFirst().metadata()).isEqualTo(metadata);
        assertThat(saved.records().get(1).page()).isEqualTo(2);
        assertThat(saved.records().get(1).chapter()).isEqualTo("章节\"一\"");
        assertThat(saved.records().get(1).section()).isEqualTo("小节\\二");
        assertThat(saved.records().get(2).metadata()).isEqualTo(Map.of("rowStart", 9));
        assertThat(saved.records()).allSatisfy(block -> assertThat(block.preview()).isNotEmpty());
        JsonNode secondPage = preview(id, "?page=2&pageSize=2", ownerToken, 200);
        assertThat(secondPage.path("records").size()).isEqualTo(1);
        assertThat(secondPage.path("records").get(0).path("blockIndex").asInt()).isEqualTo(2);
        ParsedDocument smaller = new ParsedDocument(DocumentFormat.MD, List.of(blocks.get(0)), List.of());
        context.commit(() -> repository.replace(id, 1, smaller));
        context.commit(() -> repository.replace(id, 1, smaller));
        ParsePreviewResponse reduced = repository.preview(id, 1, new PageRequest(1, 20));
        assertThat(reduced.total()).isEqualTo(1);
        assertThat(reduced.warnings()).isEmpty();
        assertThat(reduced.records()).containsExactly(saved.records().getFirst());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parse_result", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parsed_block", Long.class)).isEqualTo(1);
    }

    /** 验证后续批次失败回滚汇总、删除及先前批次。 */
    @Test
    void shouldRollBackSummaryDeletionAndEarlierBatchesWhenLaterBatchFails() throws Exception {
        long id = upload(DocumentFormat.MD, DocumentFixtures.content(DocumentFormat.MD));
        IndexTaskLease first = store.claim();
        worker.run(first);
        JsonNode original = preview(id, "", ownerToken, 200);
        store.retry(id, first.id());
        IndexTaskLease lease = store.claim();
        // 批次大小为 2 时首次 INSERT 成功，最后一个块触发数据库正文约束。
        ParsedDocument replacement = new ParsedDocument(
            DocumentFormat.MD,
            List.of(
                new ParsedBlock("first batch one", null, null, null, Map.of()),
                new ParsedBlock("first batch two", null, null, null, Map.of()),
                new ParsedBlock(" ", null, null, null, Map.of())
            ),
            List.of("replacement warning")
        );
        assertThatThrownBy(() ->
            new IndexTaskContext(lease, store).commit(() -> repository.replace(id, 1, replacement))
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(preview(id, "", ownerToken, 200)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parse_result", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parsed_block", Long.class)).isEqualTo(
            original.path("total").asLong()
        );
    }

    /** 验证需 OCR 或空 Office 内容返回明确错误而不持久化空成功。 */
    @Test
    void shouldExposeOcrAndEmptyOfficeErrorsWithoutPersistingEmptySuccess() throws Exception {
        long scan = upload(DocumentFormat.PDF, DocumentParsingServiceTest.pdf(1, false));
        IndexTaskLease task = store.claim();
        worker.run(task);
        assertThat(tasks.steps(task.id(), 100, 0).getFirst().errorCode()).isEqualTo("DOC_422_OCR_REQUIRED");
        assertThat(preview(scan, "", ownerToken, 200).path("available").asBoolean()).isFalse();
        Map<String, String> entries = new LinkedHashMap<>(DocumentFixtures.office(DocumentFormat.DOCX));
        entries.put(
            "word/document.xml",
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>"
        );
        long empty = upload(DocumentFormat.DOCX, DocumentFixtures.zip(entries));
        task = store.claim();
        worker.run(task);
        assertThat(tasks.steps(task.id(), 100, 0).getFirst().errorCode()).isEqualTo("DOC_422_EMPTY_TEXT");
        assertThat(preview(empty, "", ownerToken, 200).path("available").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parse_result", Long.class)).isZero();
    }

    /** 验证空二进制 DOC 失败且不保存空产物。 */
    @Test
    void shouldExposeEmptyBinaryDocFailureWithoutSavingAnEmptyArtifact() throws Exception {
        long id = upload(DocumentFormat.DOC, LegacyDocFixtures.read("empty.doc"));
        IndexTaskLease task = store.claim();
        worker.run(task);
        assertThat(tasks.steps(task.id(), 100, 0).getFirst().errorCode()).isEqualTo("DOC_422_EMPTY_TEXT");
        assertThat(preview(id, "", ownerToken, 200).path("available").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parse_result", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_parsed_block", Long.class)).isZero();
    }

    /** 验证六种格式均持久化真实表格行和完整 Chunk 来源。 */
    @Test
    void shouldPersistRealTableRowsAndCompleteChunkSourcesForAllSixFormats() throws Exception {
        for (DocumentFormat format : DocumentFormat.values()) {
            byte[] content = switch (format) {
                case PDF -> TableParsingTest.pdfTable();
                case DOC -> LegacyDocFixtures.read("simple-table.doc");
                case DOCX -> ParserFixtures.docx();
                case XLSX -> DocumentParsingServiceTest.excel();
                case TXT -> "Field\tKey\tType\nip_address\t\tvarchar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                case MD -> "| Field | Key | Type |\n| --- | --- | --- |\n| ip_address | | varchar |".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8
                );
            };
            long id = upload(format, content);
            worker.run(store.claim());
            JsonNode parsed = preview(id, "?pageSize=100", ownerToken, 200);
            assertThat(parsed.path("parserVersion").asText()).isEqualTo("phase6-v5-source-structure");
            List<JsonNode> rows = new ArrayList<>();
            parsed.path("records").forEach(block -> {
                if ("TABLE_ROW".equals(block.at("/metadata/kind").asText())) {
                    rows.add(block);
                }
            });
            assertThat(rows).as("%s table rows", format).isNotEmpty();
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.at("/metadata/header").isObject()).isTrue();
                assertThat(row.at("/metadata/tableCells").isObject()).isTrue();
                assertThat(row.at("/metadata/rowStart").asInt()).isPositive();
            });
            HttpHeaders headers = new HttpHeaders();
            headers.set("satoken", ownerToken);
            JsonNode chunks = http
                .exchange(
                    "/api/v1/documents/" + id + "/chunks?pageSize=100&chunkType=CHILD",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    JsonNode.class
                )
                .getBody()
                .path("data");
            assertThat(chunks.path("available").asBoolean()).isTrue();
            assertThat(chunks.path("strategyVersion").asText()).isEqualTo("structure-parent-child-v3-tables");
            List<JsonNode> tableSources = new ArrayList<>();
            chunks.path("records").forEach(chunk ->
                chunk.at("/metadata/sources").forEach(source -> {
                    if ("TABLE_ROW".equals(source.at("/metadata/kind").asText())) {
                        tableSources.add(source);
                    }
                })
            );
            assertThat(tableSources)
                .isNotEmpty()
                .allSatisfy(source -> {
                    assertThat(source.at("/metadata/tableRowComplete").asBoolean()).isTrue();
                    assertThat(source.at("/metadata/tableCells").isObject()).isTrue();
                });
        }
    }

    /** 插入测试用户并返回生成的 ID。 */
    private long user(String hash) {
        return jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id) VALUES (?,?,?) RETURNING id",
            Long.class,
            "parser." + UUID.randomUUID(),
            hash,
            department
        );
    }

    /** 提交指定文件的真实上传请求，供状态和产物断言使用。 */
    private long upload(DocumentFormat format, byte[] content) {
        ByteArrayResource file = new ByteArrayResource(content) {
            /** 为测试 multipart 内容提供上传文件名。 */
            @Override
            public String getFilename() {
                return "parser." + format.extension();
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(format.mime()));
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(file, partHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("satoken", ownerToken);
        ResponseEntity<JsonNode> response = http.postForEntity(
            "/api/v1/knowledge-bases/" + kb + "/documents",
            new HttpEntity<>(body, headers),
            JsonNode.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        return response.getBody().at("/data/documentId").asLong();
    }

    /** 请求指定版本的授权预览并返回可断言内容。 */
    private JsonNode preview(long id, String query, String token, int expected) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("satoken", token);
        }
        ResponseEntity<JsonNode> response = http.exchange(
            "/api/v1/documents/" + id + "/parsed-blocks" + query,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            JsonNode.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(expected);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        return expected < 400 ? response.getBody().path("data") : response.getBody();
    }
}
