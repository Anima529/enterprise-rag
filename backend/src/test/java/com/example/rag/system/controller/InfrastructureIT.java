package com.example.rag.system.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpUtil;
import com.example.rag.common.config.MinioProperties;
import com.example.rag.system.mapper.SystemMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = { "rag.seed.enabled=false", "rag.index-task.enabled=false" }
)
class InfrastructureIT {

    private static final int CURRENT_SCHEMA_VERSION = 21;

    @Autowired
    TestRestTemplate http;

    @Autowired
    Flyway flyway;

    @Autowired
    SystemMapper mapper;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MinioClient minio;

    @Autowired
    MinioProperties properties;

    @Autowired
    SaTokenDao tokenDao;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    com.example.rag.auth.service.PasswordService passwords;

    /** 验证已有用户原位升级，空库可迁移到当前版本。 */
    @Test
    void shouldUpgradeExistingUsersInPlaceAndMigrateAnEmptySchemaToCurrentVersion() {
        for (boolean existing : new boolean[] { true, false }) {
            String schema = "phase6_" + UUID.randomUUID().toString().replace("-", "");
            try {
                if (existing) {
                    Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                        .target("2")
                        .load()
                        .migrate();
                    String hash = passwords.hash(UUID.randomUUID().toString());
                    jdbc.update(
                        "INSERT INTO " +
                            schema +
                            ".sys_department(id,code,name) VALUES (1,'UPGRADE','existing department')"
                    );
                    jdbc.update(
                        "INSERT INTO " +
                            schema +
                            ".sys_user(id,username,password_hash,department_id) VALUES (1,'existing.user',?,1)",
                        hash
                    );
                    Flyway upgrade = Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                        .load();
                    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(CURRENT_SCHEMA_VERSION - 2);
                    assertThat(
                        jdbc.queryForObject(
                            "SELECT password_hash FROM " + schema + ".sys_user WHERE id=1",
                            String.class
                        )
                    ).isEqualTo(hash);
                    assertThat(upgrade.migrate().migrationsExecuted).isZero();
                } else {
                    Flyway fresh = Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                        .load();
                    assertThat(fresh.migrate().migrationsExecuted).isEqualTo(CURRENT_SCHEMA_VERSION);
                    assertThat(fresh.info().current().getVersion().getVersion()).isEqualTo(
                        String.valueOf(CURRENT_SCHEMA_VERSION)
                    );
                    assertThat(fresh.migrate().migrationsExecuted).isZero();
                }
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM " + schema + ".knowledge_base", Long.class)
                ).isZero();
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    /** 验证第三阶段知识库与 ACL 升级不改变现有授权。 */
    @Test
    void shouldUpgradePhase3KnowledgeBaseAndAclWithoutChangingExistingAuthorization() {
        String schema = "phase3_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("3")
                .load()
                .migrate();
            String hash = passwords.hash(UUID.randomUUID().toString());
            jdbc.update("INSERT INTO " + schema + ".sys_department(id,code,name) VALUES (1,'EXISTING','existing')");
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".sys_user(id,username,password_hash,department_id) VALUES (1,'existing.user',?,1)",
                hash
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".knowledge_base(id,name,department_id,owner_user_id) VALUES (1,'existing KB',1,1)"
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (1,'ROLE','USER','READ')"
            );
            Flyway upgrade = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(CURRENT_SCHEMA_VERSION - 3);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(
                jdbc.queryForObject("SELECT name FROM " + schema + ".knowledge_base WHERE id=1", String.class)
            ).isEqualTo("existing KB");
            assertThat(
                jdbc.queryForObject(
                    "SELECT permission FROM " + schema + ".knowledge_base_acl WHERE knowledge_base_id=1",
                    String.class
                )
            ).isEqualTo("READ");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".document", Long.class)).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** 验证第一阶段数据库升级不修改首个迁移。 */
    @Test
    void shouldUpgradeExistingPhase1SchemaToCurrentVersionWithoutChangingFirstMigration() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway baseline = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("1")
                .load();
            assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);
            Flyway upgrade = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(CURRENT_SCHEMA_VERSION - 1);
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo(
                String.valueOf(CURRENT_SCHEMA_VERSION)
            );
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".sys_user", Long.class)).isZero();
        } finally {
            // schema 名称只由 UUID 十六进制字符生成，不接收用户输入。
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** 验证空库迁移成功且重复启动不新增迁移。 */
    @Test
    void shouldMigrateEmptyDatabaseAndAllowRepeatStartupWithoutAdditionalMigrations() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(String.valueOf(CURRENT_SCHEMA_VERSION));
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(mapper.databaseInfo().vectorVersion()).isEqualTo("0.8.6");
        assertThat(mapper.databaseInfo().databaseVersion()).startsWith("16.15");
        assertThat(mapper.databaseInfo().migrations()).isEqualTo(CURRENT_SCHEMA_VERSION);
        ResponseEntity<String> status = http.getForEntity("/api/v1/system/status", String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("\"code\":0", "0.8.6", "UP");
        assertThat(status.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode()).isEqualTo(
            HttpStatus.OK
        );
    }

    /** 验证 V6 解析产物原位升级且全部格式约束允许 DOC。 */
    @Test
    void shouldUpgradeV6ParsedDocumentsInPlaceAndAllowDocInAllFormatConstraints() {
        String schema = "doc_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("6")
                .load()
                .migrate();
            String hash = passwords.hash(UUID.randomUUID().toString());
            jdbc.update("INSERT INTO " + schema + ".sys_department(id,code,name) VALUES (1,'DOCUPGRADE','existing')");
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".sys_user(id,username,password_hash,department_id) VALUES (1,'existing.doc.user',?,1)",
                hash
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".knowledge_base(id,name,department_id,owner_user_id) VALUES (1,'existing KB',1,1)"
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".knowledge_base_acl(knowledge_base_id,subject_type,subject_id,permission) VALUES (1,'ROLE','USER','READ')"
            );
            insertParsedDocument(schema, 1, "TXT", "旧正文", "a".repeat(64));
            Flyway upgrade = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(CURRENT_SCHEMA_VERSION - 6);
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo(
                String.valueOf(CURRENT_SCHEMA_VERSION)
            );
            assertThat(
                jdbc.queryForObject(
                    "SELECT text FROM " + schema + ".document_parsed_block WHERE document_id=1",
                    String.class
                )
            ).isEqualTo("旧正文");
            assertThat(
                jdbc.queryForObject("SELECT password_hash FROM " + schema + ".sys_user WHERE id=1", String.class)
            ).isEqualTo(hash);
            assertThat(
                jdbc.queryForObject(
                    "SELECT permission FROM " + schema + ".knowledge_base_acl WHERE knowledge_base_id=1",
                    String.class
                )
            ).isEqualTo("READ");
            insertParsedDocument(schema, 2, "DOC", "DOC 中文正文", "b".repeat(64));
            for (String table : new String[] { "document", "document_version", "document_parse_result" }) {
                String column = table.equals("document") ? "id" : "document_id";
                assertThat(
                    jdbc.queryForObject(
                        "SELECT source_format FROM " + schema + "." + table + " WHERE " + column + "=2",
                        String.class
                    )
                ).isEqualTo("DOC");
            }
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** 验证 V8 Chunk 升级不重写历史切分边界。 */
    @Test
    void shouldUpgradeV8ChunksWithoutRewritingHistoricalBoundaries() {
        String schema = "chunk_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("8")
                .load()
                .migrate();
            String hash = passwords.hash(UUID.randomUUID().toString());
            jdbc.update("INSERT INTO " + schema + ".sys_department(id,code,name) VALUES (1,'CHUNKUPGRADE','existing')");
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".sys_user(id,username,password_hash,department_id) VALUES (1,'existing.chunk.user',?,1)",
                hash
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".knowledge_base(id,name,department_id,owner_user_id) VALUES (1,'existing KB',1,1)"
            );
            insertParsedDocument(schema, 1, "TXT", "旧正文", "a".repeat(64));
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(transaction -> {
                jdbc.update(
                    "INSERT INTO " +
                        schema +
                        ".document_chunk_result(document_id,document_version,strategy_version,tokenizer,child_tokens,parent_tokens,overlap_tokens,parent_count,child_count) VALUES (1,1,'structure-parent-child-v1','cl100k_base',32,128,5,1,1)"
                );
                jdbc.update(
                    "INSERT INTO " +
                        schema +
                        ".document_chunk(id,knowledge_base_id,department_id,document_id,document_version,chunk_type,chunk_index,content,token_count,metadata) VALUES (1,1,1,1,1,'PARENT',0,'旧正文',5,'{}'::jsonb)"
                );
                jdbc.update(
                    "INSERT INTO " +
                        schema +
                        ".document_chunk(id,knowledge_base_id,department_id,document_id,document_version,parent_chunk_id,parent_type,chunk_type,chunk_index,content,token_count,metadata) VALUES (2,1,1,1,1,1,'PARENT','CHILD',1,'旧正文',5,'{}'::jsonb)"
                );
            });
            Flyway upgrade = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(CURRENT_SCHEMA_VERSION - 8);
            assertThat(
                jdbc.queryForObject(
                    "SELECT boundary_lookback_characters FROM " + schema + ".document_chunk_result",
                    Integer.class
                )
            ).isNull();
            assertThat(
                jdbc.queryForObject("SELECT strategy_version FROM " + schema + ".document_chunk_result", String.class)
            ).isEqualTo("structure-parent-child-v1");
            assertThat(
                jdbc.queryForList("SELECT content FROM " + schema + ".document_chunk ORDER BY id", String.class)
            ).containsExactly("旧正文", "旧正文");
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** 验证 V15 升级不重新标记已有向量模型。 */
    @Test
    void shouldUpgradeV15WithoutRelabelingExistingVectors() {
        String schema = "model_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String vector = "[1" + ",0".repeat(1023) + "]";
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("15")
                .load()
                .migrate();
            jdbc.update("INSERT INTO " + schema + ".sys_department(id,code,name) VALUES (1,'MODELUPGRADE','existing')");
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".sys_user(id,username,password_hash,department_id) VALUES (1,'existing.model.user',?,1)",
                passwords.hash(UUID.randomUUID().toString())
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".knowledge_base(id,name,department_id,owner_user_id) VALUES (1,'existing KB',1,1)"
            );
            insertParsedDocument(schema, 1, "TXT", "旧正文", "a".repeat(64));
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(transaction -> {
                jdbc.update(
                    "INSERT INTO " +
                        schema +
                        ".document_chunk_result(document_id,document_version,strategy_version,tokenizer,child_tokens,parent_tokens,overlap_tokens,parent_count,child_count) VALUES (1,1,'structure-parent-child-v1','cl100k_base',32,128,5,1,1)"
                );
                jdbc.update(
                    "INSERT INTO " +
                        schema +
                        ".document_chunk(id,knowledge_base_id,department_id,document_id,document_version,chunk_type,chunk_index,content,token_count,metadata) VALUES (1,1,1,1,1,'PARENT',0,'旧正文',5,'{}'::jsonb)"
                );
                jdbc.update(
                    "INSERT INTO " +
                        schema +
                        ".document_chunk(id,knowledge_base_id,department_id,document_id,document_version,parent_chunk_id,parent_type,chunk_type,chunk_index,content,token_count,metadata,embedding,embedding_model,embedding_dimensions,embedding_content_hash) VALUES (2,1,1,1,1,1,'PARENT','CHILD',1,'旧正文',5,'{}'::jsonb,CAST(? AS vector),'text-embedding-v3',1024,?)",
                    vector,
                    "a".repeat(64)
                );
                jdbc.update(
                    "INSERT INTO " +
                        schema +
                        ".document_embedding_result(document_id,document_version,model,dimensions,chunked_at,child_count,batch_count,call_count,total_tokens,duration_ms) SELECT 1,1,'text-embedding-v3',1024,updated_at,1,1,1,0,0 FROM " +
                        schema +
                        ".document_chunk_result"
                );
            });
            Flyway upgrade = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(CURRENT_SCHEMA_VERSION - 15);
            assertThat(
                jdbc.queryForObject(
                    "SELECT embedding_model FROM " + schema + ".document_chunk WHERE id=2",
                    String.class
                )
            ).isEqualTo("text-embedding-v3");
            assertThat(
                jdbc.queryForObject("SELECT model FROM " + schema + ".document_embedding_result", String.class)
            ).isEqualTo("text-embedding-v3");
            assertThat(
                jdbc.queryForObject(
                    "SELECT embedding::text FROM " + schema + ".document_chunk WHERE id=2",
                    String.class
                )
            ).isEqualTo(vector);
            jdbc.update(
                "UPDATE " + schema + ".document_chunk SET embedding_model='qwen3.7-text-embedding-flash' WHERE id=2"
            );
            jdbc.update("UPDATE " + schema + ".document_embedding_result SET model='qwen3.7-text-embedding-flash'");
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** 写入历史版本解析产物，供迁移兼容性断言。 */
    private void insertParsedDocument(String schema, long id, String format, String text, String sha) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(transaction -> {
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".document(id,knowledge_base_id,name,source_format,mime_type,size_bytes,sha256,object_key,created_by) VALUES (?,1,'existing',?,'application/octet-stream',10,?,?,1)",
                id,
                format,
                sha,
                "doc-upgrade-" + id
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".document_version(document_id,version_no,source_format,mime_type,size_bytes,sha256,object_key) VALUES (?,1,?,'application/octet-stream',10,?,?)",
                id,
                format,
                sha,
                "doc-upgrade-" + id
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".document_parse_result(document_id,document_version,source_format,parser_version,block_count,character_count) VALUES (?,1,?,'phase6-v1',1,?)",
                id,
                format,
                text.length()
            );
            jdbc.update(
                "INSERT INTO " +
                    schema +
                    ".document_parsed_block(document_id,document_version,block_index,text,metadata) VALUES (?,1,0,?,'{}'::jsonb)",
                id,
                text
            );
        });
    }

    /** 验证 Redis 和私有 MinIO 对象持久化且测试键范围受限。 */
    @Test
    void shouldPersistRedisAndPrivateMinioObjectsWithBoundedTestKeys() throws Exception {
        String key = "rag:test:" + UUID.randomUUID();
        String object = "phase1-test/" + UUID.randomUUID() + ".txt";
        byte[] content = "Phase 1 storage roundtrip".getBytes(StandardCharsets.UTF_8);
        try {
            redis.opsForValue().set(key, "ok", Duration.ofSeconds(30));
            assertThat(redis.opsForValue().get(key)).isEqualTo("ok");
            minio.putObject(
                PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(object)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType("text/plain")
                    .build()
            );
            try (
                GetObjectResponse stream = minio.getObject(
                    GetObjectArgs.builder().bucket(properties.bucket()).object(object).build()
                )
            ) {
                assertThat(stream.readAllBytes()).isEqualTo(content);
            }
        } finally {
            redis.delete(key);
            minio.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(object).build());
        }
    }

    /** 验证 Redis 会话仅使用请求头 Token 且拒绝未知主体。 */
    @Test
    void shouldUseRedisBackedHeaderOnlySessionsAndRejectUnknownPrincipals() {
        String token = StpUtil.createLoginSession(900001);
        try {
            var headers = new HttpHeaders();
            headers.set("satoken", token);
            assertThat(
                http
                    .exchange("/api/v1/admin/missing", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getStatusCode()
            ).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(
                http.getForEntity("/api/v1/knowledge-bases?satoken=" + token, String.class).getStatusCode()
            ).isEqualTo(HttpStatus.UNAUTHORIZED);
            var cookieHeaders = new HttpHeaders();
            cookieHeaders.set(HttpHeaders.COOKIE, "satoken=" + token);
            assertThat(
                http
                    .exchange("/api/v1/knowledge-bases", HttpMethod.GET, new HttpEntity<>(cookieHeaders), String.class)
                    .getStatusCode()
            ).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(tokenDao.getClass().getName()).contains("Redis");
            StpUtil.logout(900001);
            assertThat(
                http
                    .exchange("/api/v1/knowledge-bases", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getStatusCode()
            ).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            StpUtil.logout(900001);
        }
    }
}
