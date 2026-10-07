package com.example.rag.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.system.domain.DatabaseInfo;
import com.example.rag.system.dto.SystemStatusResponse;
import com.example.rag.system.service.SystemApplicationService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "rag.index-task.enabled=false",
        "management.endpoint.health.group.readiness.include=readinessState",
        "POSTGRES_PASSWORD=test-only",
        "REDIS_PASSWORD=test-only",
        "MINIO_ACCESS_KEY=test-only",
        "MINIO_SECRET_KEY=test-only",
    }
)
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
@Import(WebBoundaryTest.FailureController.class)
class WebBoundaryTest {

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

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SystemApplicationService service;

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
    com.example.rag.system.mapper.SystemMapper mapper;

    @MockitoBean
    com.example.rag.user.mapper.UserMapper userMapper;

    @MockitoBean
    com.example.rag.department.mapper.DepartmentMapper departmentMapper;

    @MockitoBean
    com.example.rag.knowledgebase.mapper.KnowledgeBaseMapper knowledgeBaseMapper;

    @MockitoBean
    com.example.rag.auth.mapper.seed.DevSeedMapper seedMapper;

    @RestController
    static class FailureController {

        /** 提取或断言用例期望的公开业务错误。 */
        @GetMapping("/test/internal-error")
        void error() {
            throw new IllegalStateException("password=should-never-appear-in-response");
        }

        /** 提供会抛出业务异常的测试接口，验证统一错误映射。 */
        @GetMapping("/test/business-error")
        void business() {
            throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
        }
    }

    /** 验证返回真实服务结果且追踪 ID 由服务端生成。 */
    @Test
    void shouldReturnRealServiceResponseAndGenerateTraceInsteadOfTrustingClientHeaders() throws Exception {
        when(service.status()).thenReturn(
            new SystemStatusResponse(
                "enterprise-rag",
                Instant.EPOCH,
                new DatabaseInfo("16.15", "0.8.6", 1),
                Map.of("postgres", "UP")
            )
        );
        MvcResult result = mvc
            .perform(get("/api/v1/system/status").header("X-Request-Id", "forged").header("X-Trace-Id", "forged"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.database.vectorVersion").value("0.8.6"))
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(header().exists("X-Trace-Id"))
            .andReturn();
        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotEqualTo("forged");
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isNotEqualTo("forged");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    /** 验证非法查询参数返回可追踪的字符串错误码。 */
    @Test
    void shouldRejectInvalidQueryParametersWithTraceableStringError() throws Exception {
        MvcResult result = mvc
            .perform(get("/api/v1/system/status").param("locale", "invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_400_VALIDATION"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains(result.getResponse().getHeader("X-Request-Id"));
    }

    /** 验证业务接口要求请求头登录凭据，忽略 Cookie 和查询 Token。 */
    @Test
    void shouldRequireLoginForBusinessRoutesAndIgnoreCookieAndQueryToken() throws Exception {
        mvc.perform(
            get("/api/v1/knowledge-bases").cookie(new Cookie("satoken", "test-token")).param("satoken", "test-token")
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_401_UNAUTHENTICATED"));
    }

    /** 验证未知路径和方法仍使用统一错误协议。 */
    @Test
    void shouldMapUnknownPathsAndMethodsWithoutSpringErrorBody() throws Exception {
        mvc.perform(get("/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("COMMON_404_NOT_FOUND"));
        mvc.perform(post("/api/v1/system/status"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("COMMON_405_METHOD_NOT_ALLOWED"));
    }

    /** 验证隐藏内部异常细节并保留业务错误状态。 */
    @Test
    void shouldHideInternalExceptionDetailsAndPreserveBusinessStatus() throws Exception {
        mvc.perform(get("/test/internal-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("COMMON_500_INTERNAL"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
        mvc.perform(get("/test/business-error"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("STORAGE_503_UNAVAILABLE"));
    }
}
