package com.example.rag.governance.mapper;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.auth.service.PasswordService;
import com.example.rag.governance.domain.RequestLogStatus;
import com.example.rag.governance.dto.AdminLogModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = { "rag.seed.enabled=false", "rag.index-task.enabled=false", "rag.evaluation.enabled=false" }
)
@Transactional
class AdminLogInfrastructureIT {

    @Autowired
    GovernanceMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PasswordService passwords;

    private long userId;
    private long departmentId;

    /** 合成记录在事务结束后回滚，不修改共享环境的业务日志。 */
    @BeforeEach
    void prepare() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        departmentId = jdbc.queryForObject(
            "INSERT INTO sys_department(code, name) VALUES (?, '日志测试部门') RETURNING id",
            Long.class,
            "LG" + suffix.substring(0, 24).toUpperCase(Locale.ROOT)
        );
        userId = jdbc.queryForObject(
            "INSERT INTO sys_user(username, password_hash, department_id) VALUES (?, ?, ?) RETURNING id",
            Long.class,
            "logs." + suffix,
            passwords.hash(suffix),
            departmentId
        );
    }

    /** 同时间审计通过 ID 稳定分页，用户删除后仍可查询历史，精确标识不能注入 SQL。 */
    @Test
    void shouldFilterAndPageAuditLogsWithoutLosingDeletedActors() {
        long first = insertAudit("request-a", "2026-10-05T16:00:00Z");
        long second = insertAudit("request-b", "2026-10-05T16:00:00Z");
        insertAudit("request-excluded", "2026-10-06T16:00:00Z");
        LogFilter filter = filter(null, "DATASET_CREATE", null);
        assertThat(mapper.countAuditLogs(filter)).isEqualTo(2);
        assertThat(mapper.auditLogs(filter, 1, 0))
            .extracting(AuditLog::id)
            .containsExactly(second);
        assertThat(mapper.auditLogs(filter, 1, 1))
            .extracting(AuditLog::id)
            .containsExactly(first);
        assertThat(mapper.auditLogs(filter("request-a", null, null), 20, 0)).hasSize(1);
        assertThat(mapper.auditLogs(filter("' OR 1=1 --", null, null), 20, 0)).isEmpty();
        jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
        LogFilter deletedActor = new LogFilter(filter.from(), filter.to(), null, "request-a", null, null);
        AuditLog history = mapper.auditLogs(deletedActor, 20, 0).getFirst();
        assertThat(history.userId()).isNull();
        assertThat(history.username()).isNull();
        assertThat(history.traceId()).isEqualTo("trace-test");
    }

    /** 请求查询保留失败、运行中、未知首 Token 与错误码，结束日午夜属于次日。 */
    @Test
    void shouldFilterRequestMetadataAndExcludeEndBoundary() throws Exception {
        insertRequest("logs-failed", "FAILED", "2026-10-05T16:00:00Z");
        insertRequest("logs-running", "RUNNING", "2026-10-06T15:59:59Z");
        insertRequest("logs-excluded", "FAILED", "2026-10-06T16:00:00Z");
        long conversationId = jdbc.queryForObject(
            "INSERT INTO conversation(user_id, title) VALUES (?, '日志测试') RETURNING id",
            Long.class,
            userId
        );
        long messageId = jdbc.queryForObject(
            "INSERT INTO chat_message(conversation_id, user_id, role, content, request_id, trace_id, status, " +
                "error_code, knowledge_base_ids, expires_at) VALUES (?, ?, 'ASSISTANT', 'private-content-sentinel', " +
                "'logs-failed', 'trace-test', 'FAILED', 'RAG_503_MODEL_UNAVAILABLE', '[]'::jsonb, now()) RETURNING id",
            Long.class,
            conversationId,
            userId
        );
        jdbc.update("UPDATE rag_request_trace SET message_id = ? WHERE request_id = 'logs-failed'", messageId);
        LogFilter filter = filter(null, null, RequestLogStatus.FAILED);
        assertThat(mapper.countRequestLogs(filter)).isEqualTo(1);
        List<RequestLog> requests = mapper.requestLogs(filter, 20, 0);
        assertThat(requests).hasSize(1);
        RequestLog failed = requests.getFirst();
        assertThat(failed.status()).isEqualTo(RequestLogStatus.FAILED);
        assertThat(failed.firstTokenMs()).isNull();
        assertThat(failed.departmentName()).isEqualTo("日志测试部门");
        assertThat(failed.cacheHit()).isFalse();
        assertThat(failed.errorCode()).isEqualTo("RAG_503_MODEL_UNAVAILABLE");
        assertThat(json.writeValueAsString(failed)).doesNotContain("private-content-sentinel", "password_hash");
        assertThat(mapper.requestLogs(filter(null, null, null), 1, 0))
            .extracting(RequestLog::requestId)
            .containsExactly("logs-running");
        assertThat(mapper.requestLogs(filter("logs-failed", null, null), 20, 0)).hasSize(1);
        assertThat(mapper.requestLogs(filter("' OR 1=1 --", null, null), 20, 0)).isEmpty();
    }

    /** 创建同一操作者的审计夹具，不含密码和请求正文。 */
    private long insertAudit(String requestId, String time) {
        return jdbc.queryForObject(
            "INSERT INTO audit_log(user_id, action, request_id, trace_id, created_at) " +
                "VALUES (?, 'DATASET_CREATE', ?, 'trace-test', ?::timestamptz) RETURNING id",
            Long.class,
            userId,
            requestId,
            time
        );
    }

    /** 创建请求追踪夹具，不调用模型、不保存会话正文。 */
    private void insertRequest(String requestId, String status, String time) {
        jdbc.update(
            "INSERT INTO rag_request_trace(request_id, trace_id, user_id, department_id, status, created_at) " +
                "VALUES (?, 'trace-test', ?, ?, ?, ?::timestamptz)",
            requestId,
            userId,
            departmentId,
            status,
            time
        );
    }

    /** 北京时间十月六日对应前日十六点至当日十六点的半开区间。 */
    private LogFilter filter(String requestId, String action, RequestLogStatus status) {
        return new LogFilter(
            Instant.parse("2026-10-05T16:00:00Z"),
            Instant.parse("2026-10-06T16:00:00Z"),
            userId,
            requestId,
            action,
            status
        );
    }
}
