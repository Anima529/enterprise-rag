package com.example.rag.evaluation.repository;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.auth.service.PasswordService;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.evaluation.dto.EvaluationModels.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = { "rag.seed.enabled=false", "rag.index-task.enabled=false", "rag.evaluation.enabled=false" }
)
class EvaluationRepositoryIT {

    @Autowired
    EvaluationRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordService passwords;

    private long actorId;
    private long departmentId;
    private long datasetId;
    private long caseId;

    /** 每题使用独立账号及数据集，不删除其他测试或开发数据。 */
    @BeforeEach
    void prepare() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        departmentId = jdbc.queryForObject(
            "INSERT INTO sys_department(code,name) VALUES (?, '评估仓储测试') RETURNING id",
            Long.class,
            "EV" + suffix.substring(0, 24).toUpperCase(Locale.ROOT)
        );
        actorId = jdbc.queryForObject(
            "INSERT INTO sys_user(username,password_hash,department_id) VALUES (?,?,?) RETURNING id",
            Long.class,
            "eval." + suffix,
            passwords.hash(suffix),
            departmentId
        );
        CaseInput input = new CaseInput("公开测试问题", List.of(1L), List.of(), "", "", false, "repository");
        datasetId = repository.create(new DatasetRequest("仓储测试", "", actorId, List.of(input)), actorId);
        caseId = ((Number) repository.cases(datasetId).getFirst().get("id")).longValue();
    }

    /** 先清理本次实验与案例，再清理身份，审计仍保留无凭证的历史动作。 */
    @AfterEach
    void clean() {
        jdbc.update(
            "DELETE FROM evaluation_result WHERE run_id IN (SELECT id FROM evaluation_run WHERE dataset_id=?)",
            datasetId
        );
        jdbc.update("DELETE FROM evaluation_run WHERE dataset_id=?", datasetId);
        jdbc.update("DELETE FROM evaluation_dataset WHERE id=?", datasetId);
        jdbc.update("DELETE FROM sys_user WHERE id=?", actorId);
        jdbc.update("DELETE FROM sys_department WHERE id=?", departmentId);
    }

    /** 两实例并发领取两个任务，各任务只被一人领取，结果快照不能重复覆盖。 */
    @Test
    void shouldClaimDistinctRunsAndRejectDuplicateCaseResults() throws Exception {
        long firstId = enqueue("first", 2);
        long secondId = enqueue("second", 2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Map<String, Object>> first = pool.submit(() -> repository.claim("worker-a", 10000));
            Future<Map<String, Object>> second = pool.submit(() -> repository.claim("worker-b", 10000));
            Map<String, Object> a = first.get(5, TimeUnit.SECONDS);
            Map<String, Object> b = second.get(5, TimeUnit.SECONDS);
            assertThat(
                List.of(((Number) a.get("id")).longValue(), ((Number) b.get("id")).longValue())
            ).containsExactlyInAnyOrder(firstId, secondId);
            assertThat(repository.claim("worker-c", 10000)).isNull();
            long ownedId = ((Number) a.get("id")).longValue();
            repository.result(ownedId, caseId, "worker-a", Map.of("answer", "first"));
            assertThatThrownBy(() ->
                repository.result(ownedId, caseId, "worker-a", Map.of("answer", "changed"))
            ).isInstanceOf(IllegalStateException.class);
            assertThat(
                jdbc.queryForObject(
                    "SELECT result_snapshot->>'answer' FROM evaluation_result WHERE run_id=?",
                    String.class,
                    ownedId
                )
            ).isEqualTo("first");
        }
    }

    /** 服务重启遗留租约到期后明确失败，旧执行者不能续租、写结果或覆盖失败终态。 */
    @Test
    void shouldFailExpiredRunAndRejectStaleWorkerWrites() {
        long id = enqueue("expired", 2);
        repository.claim("old-worker", 10000);
        jdbc.update("UPDATE evaluation_run SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
        assertThat(repository.renew(id, "old-worker", 10000)).isFalse();
        assertThatThrownBy(() -> repository.result(id, caseId, "old-worker", Map.of("answer", "late"))).isInstanceOf(
            IllegalStateException.class
        );
        assertThat(repository.claim("new-worker", 10000)).isNull();
        repository.finish(id, "old-worker", Map.of("caseCount", 1), null);
        Map<String, Object> run = repository.run(id);
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_code")).isEqualTo("EVAL_LEASE_EXPIRED");
    }

    /** 全局队列达到预算时拒绝新任务，不能以重复 POST 无界堆积模型费用。 */
    @Test
    void shouldBoundPendingQueueWithoutMutatingDataset() {
        enqueue("bounded", 1);
        assertThatThrownBy(() -> enqueue("overflow", 1)).isInstanceOf(BusinessException.class);
        assertThat(repository.runs(datasetId, 10)).hasSize(1);
        assertThat(repository.cases(datasetId)).hasSize(1);
    }

    /** 使用纯合成配置快照入队，仓储测试不执行任何模型调用。 */
    private long enqueue(String name, int maximum) {
        return repository.enqueue(
            datasetId,
            new RunRequest(name, Mode.VECTOR),
            Map.of("synthetic", true),
            actorId,
            maximum
        );
    }
}
