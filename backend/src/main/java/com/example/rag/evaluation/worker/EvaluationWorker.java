package com.example.rag.evaluation.worker;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.conversation.support.CitationMarkerFilter;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.evaluation.config.EvaluationProperties;
import com.example.rag.evaluation.domain.EvaluationProtocol;
import com.example.rag.evaluation.dto.EvaluationModels.*;
import com.example.rag.evaluation.repository.EvaluationRepository;
import com.example.rag.evaluation.service.EvaluationService;
import com.example.rag.evaluation.support.RetrievalMetrics;
import com.example.rag.governance.service.RagConfigurationSnapshot;
import com.example.rag.governance.service.RedisCostGuard;
import com.example.rag.rag.adaptive.domain.AdaptiveProtocol;
import com.example.rag.rag.adaptive.service.StructuredModelService;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.chat.service.ContextBuilder;
import com.example.rag.rag.faithfulness.domain.VerificationProtocol;
import com.example.rag.rag.faithfulness.service.EvidenceJudge;
import com.example.rag.rag.model.domain.ModelStage;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.rag.prompt.service.PromptCatalog;
import com.example.rag.rag.retrieve.config.RetrievalProperties.Complexity;
import com.example.rag.rag.retrieve.service.HybridRetrievalService;
import com.example.rag.user.service.UserApplicationService;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 持久异步实验不经过答案缓存，三种模式共享同一 Rewrite、候选预算、入模规则和 Judge。 */
@Component
public class EvaluationWorker {

    private static final Logger log = LoggerFactory.getLogger(EvaluationWorker.class);
    private final EvaluationRepository repository;
    private final EvaluationService service;
    private final EvaluationProperties config;
    private final PermissionScopeService permissions;
    private final HybridRetrievalService retrieval;
    private final ContextBuilder contexts;
    private final GenerationGateway generation;
    private final StructuredModelService structured;
    private final EvidenceJudge judge;
    private final PromptCatalog prompts;
    private final RagConfigurationSnapshot configuration;
    private final UserApplicationService users;
    private final RedisCostGuard guard;
    private final ChunkTokenizer tokenizer;
    private final ChatProperties chat;
    private final ObjectMapper json;
    private final ExecutorService runs = Executors.newSingleThreadExecutor();
    private final ExecutorService cases = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final String owner = UUID.randomUUID().toString();

    /** 配置后台执行资源，单题有独立总超时，任务领取与写入都有租约保护。 */
    public EvaluationWorker(
        EvaluationRepository repository,
        EvaluationService service,
        EvaluationProperties config,
        PermissionScopeService permissions,
        HybridRetrievalService retrieval,
        ContextBuilder contexts,
        GenerationGateway generation,
        StructuredModelService structured,
        EvidenceJudge judge,
        PromptCatalog prompts,
        RagConfigurationSnapshot configuration,
        UserApplicationService users,
        RedisCostGuard guard,
        ChunkTokenizer tokenizer,
        ChatProperties chat,
        ObjectMapper json
    ) {
        this.repository = repository;
        this.service = service;
        this.config = config;
        this.permissions = permissions;
        this.retrieval = retrieval;
        this.contexts = contexts;
        this.generation = generation;
        this.structured = structured;
        this.judge = judge;
        this.prompts = prompts;
        this.configuration = configuration;
        this.users = users;
        this.guard = guard;
        this.tokenizer = tokenizer;
        this.chat = chat;
        this.json = json;
    }

    /** 调度线程只领取和派发，不能阻塞文档索引与其他定时任务。 */
    @Scheduled(fixedDelayString = "${rag.evaluation.poll-interval}")
    public void poll() {
        if (!config.enabled() || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, Object> run = repository.claim(owner, config.lease().toMillis());
            if (run == null) {
                busy.set(false);
                return;
            }
            runs.submit(() -> execute(run));
        } catch (RuntimeException failure) {
            busy.set(false);
            log.warn("evaluation claim failed, exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    /** 校验被冻结的配置与每题范围，全部执行后保存明确的分母和错误数。 */
    private void execute(Map<String, Object> run) {
        long id = ((Number) run.get("id")).longValue();
        List<Map<String, Object>> results = new ArrayList<>();
        String error = null;
        try {
            JsonNode snapshot = (JsonNode) run.get("snapshot");
            if (!configuration.digest().equals(snapshot.path("configurationHash").asText())) {
                throw new BusinessException(ErrorCode.EVAL_409_SNAPSHOT_CHANGED);
            }
            long userId = snapshot.path("evaluationUserId").longValue();
            Mode mode = Mode.valueOf(run.get("mode").toString());
            for (JsonNode row : snapshot.path("cases")) {
                long caseId = row.path("id").longValue();
                CaseInput input = json.convertValue(row.path("snapshot"), CaseInput.class);
                String fingerprint = snapshot.path("scopeFingerprints").path(Long.toString(caseId)).asText();
                verifySnapshot(userId, input, fingerprint);
                if (!repository.renew(id, owner, config.lease().toMillis())) {
                    throw new IllegalStateException("Evaluation lease lost");
                }
                Map<String, Object> result = boundedCase(id, caseId, userId, mode, input, fingerprint);
                verifySnapshot(userId, input, fingerprint);
                repository.result(id, caseId, owner, result);
                results.add(result);
            }
        } catch (BusinessException failure) {
            error = failure.errorCode().name();
        } catch (RuntimeException failure) {
            error = EvaluationProtocol.INTERRUPTED;
            log.warn("evaluation run failed, runId={}, exceptionType={}", id, failure.getClass().getSimpleName());
        } finally {
            try {
                repository.finish(id, owner, summarize(results), error);
            } finally {
                busy.set(false);
            }
        }
    }

    /** 独立单题执行器在总预算到期时取消上游，错误题仍以零召回进入相关题分母。 */
    private Map<String, Object> boundedCase(
        long runId,
        long caseId,
        long userId,
        Mode mode,
        CaseInput input,
        String fingerprint
    ) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Future<Map<String, Object>> future = cases.submit(() -> evaluate(userId, mode, input, fingerprint, requestId));
        Map<String, Object> result;
        try {
            result = future.get(config.caseTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Evaluation interrupted", failure);
            }
            Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
            String code =
                cause instanceof BusinessException business
                    ? business.errorCode().name()
                    : EvaluationProtocol.CASE_FAILED;
            result = new LinkedHashMap<>();
            result.put("errorCode", code);
            result.put("metrics", RetrievalMetrics.calculate(input.expectedChunkIds(), List.of(), config.metricK()));
        }
        result.put("question", input.question());
        result.put("category", input.category());
        result.put("requestId", requestId);
        result.put("traceId", requestId);
        result.put("runId", runId);
        result.put("caseId", caseId);
        result.put("latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return result;
    }

    /** 所有召回仍使用执行身份的实时 ACL；权限负例在任何模型调用之前拒绝。 */
    private Map<String, Object> evaluate(long userId, Mode mode, CaseInput input, String fingerprint, String requestId)
        throws Exception {
        try (
            MDC.MDCCloseable request = MDC.putCloseable(TraceContextKeys.REQUEST_ID, requestId);
            MDC.MDCCloseable trace = MDC.putCloseable(TraceContextKeys.TRACE_ID, requestId)
        ) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (EvaluationProtocol.DENIED.equals(fingerprint)) {
                result.put("permissionPassed", input.expectDenied());
                result.put("actualChunkIds", List.of());
                result.put("metrics", RetrievalMetrics.calculate(List.of(), List.of(), config.metricK()));
                return result;
            }
            if (input.expectDenied()) {
                throw new BusinessException(ErrorCode.EVAL_409_SNAPSHOT_CHANGED);
            }
            guard.request(users.requireActive(userId));
            RetrievalScope scope = permissions.resolveFor(userId, input.knowledgeBaseIds());
            String query = rewrite(input);
            HybridRetrievalService.Result search = retrieval.retrieveExperiment(
                scope,
                query,
                query,
                Complexity.STANDARD,
                config.caseTimeout(),
                mode != Mode.VECTOR,
                mode == Mode.HYBRID_RERANK
            );
            List<Long> ranked = search
                .ranked()
                .stream()
                .map(candidate -> candidate.hit().chunkId())
                .toList();
            ContextBuilder.Context context = contexts.buildCandidates(query, search.selected());
            String answer = generate(context);
            EvidenceJudge.Verification verification = judge.verify(query, answer, context.evidence());
            verifySnapshot(userId, input, fingerprint);
            result.put("query", query);
            result.put("actualChunkIds", ranked);
            result.put("search", search);
            result.put("metrics", RetrievalMetrics.calculate(input.expectedChunkIds(), ranked, config.metricK()));
            result.put("answer", answer);
            result.put("citations", context.citations());
            result.put("verification", verification);
            result.put(
                "faithfulness",
                VerificationProtocol.UNKNOWN.equals(verification.status()) ? null : verification.coverage()
            );
            result.put("answerRelevance", relevance(input, answer));
            result.put("judgeVersion", EvaluationProtocol.JUDGE_VERSION);
            result.put("noAnswerPassed", input.expectedChunkIds().isEmpty() ? context.citations().isEmpty() : null);
            return result;
        }
    }

    /** 多轮省略案例使用固定历史与现有 Rewrite Prompt；失败计入该题错误，避免隐藏实验差异。 */
    private String rewrite(CaseInput input) throws Exception {
        if (tokenizer.count(input.question()) > chat.questionTokens()) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        if (input.history().isBlank()) {
            return input.question();
        }
        JsonNode rewritten = structured.call(
            ModelStage.REWRITE.name(),
            prompts.rewrite(),
            json.writeValueAsString(
                Map.of(AdaptiveProtocol.HISTORY, input.history(), AdaptiveProtocol.QUESTION, input.question())
            ),
            config.judgeOutputTokens(),
            config.caseTimeout()
        );
        JsonNode query = rewritten.path(AdaptiveProtocol.QUERY);
        if (!query.isTextual() || query.asText().isBlank() || tokenizer.count(query.asText()) > chat.questionTokens()) {
            throw new IllegalArgumentException("Invalid evaluation rewrite");
        }
        return query.asText();
    }

    /** 收集有界公开回答并使用与 SSE 相同的引用白名单，不展示模型私有推理。 */
    private String generate(ContextBuilder.Context context) {
        if (context.citations().isEmpty()) {
            return "当前所选知识库没有足够证据，无法确认该问题。";
        }
        Set<String> ids = context.evidence().keySet();
        CitationMarkerFilter markers = new CitationMarkerFilter(ids);
        StringBuilder answer = new StringBuilder();
        generation
            .generate(context.request())
            .doOnNext(delta -> {
                if (delta.delta() != null) {
                    if (answer.length() + delta.delta().length() > chat.maxOutputCharacters()) {
                        throw new IllegalArgumentException("Evaluation answer exceeds budget");
                    }
                    answer.append(markers.append(delta.delta()));
                }
            })
            .blockLast(config.caseTimeout());
        answer.append(markers.finish());
        if (answer.isEmpty()) {
            throw new BusinessException(ErrorCode.RAG_503_MODEL_UNAVAILABLE);
        }
        return answer.toString();
    }

    /** 相关性 Judge 失败保持未知，并保存固定模型/Prompt 版本而非编造评分。 */
    private Double relevance(CaseInput input, String answer) {
        try {
            JsonNode result = structured.call(
                ModelStage.FAITHFULNESS.name(),
                prompts.relevance(),
                json.writeValueAsString(
                    Map.of("question", input.question(), "answer", answer, "referenceAnswer", input.referenceAnswer())
                ),
                config.judgeOutputTokens(),
                config.caseTimeout()
            );
            JsonNode score = result.path("score");
            if (
                !score.isNumber() ||
                !Double.isFinite(score.doubleValue()) ||
                score.doubleValue() < 0 ||
                score.doubleValue() > 1
            ) {
                return null;
            }
            return score.doubleValue();
        } catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Evaluation relevance interrupted", failure);
            }
            return null;
        }
    }

    /** 变化后停止实验，不能把不同 ACL 或文档版本的题目混成同一次可比结果。 */
    private void verifySnapshot(long userId, CaseInput input, String fingerprint) {
        if (!fingerprint.equals(service.fingerprint(userId, input))) {
            throw new BusinessException(ErrorCode.EVAL_409_SNAPSHOT_CHANGED);
        }
    }

    /** 对全部相关题做宏平均，未知 Judge 单独计数，失败题的召回为零。 */
    private Map<String, Object> summarize(List<Map<String, Object>> results) {
        List<Metrics> metrics = results
            .stream()
            .map(row -> (Metrics) row.get("metrics"))
            .filter(value -> value.hit() != null)
            .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", results.size());
        summary.put("retrievalDenominator", metrics.size());
        summary.put("metricK", config.metricK());
        summary.put("hitRate", metrics.stream().mapToDouble(Metrics::hit).average().orElse(Double.NaN));
        summary.put("recall", metrics.stream().mapToDouble(Metrics::recall).average().orElse(Double.NaN));
        summary.put("mrr", metrics.stream().mapToDouble(Metrics::reciprocalRank).average().orElse(Double.NaN));
        for (String field : List.of("faithfulness", "answerRelevance")) {
            DoubleSummaryStatistics values = results
                .stream()
                .map(row -> row.get(field))
                .filter(Number.class::isInstance)
                .mapToDouble(value -> ((Number) value).doubleValue())
                .summaryStatistics();
            summary.put(field, values.getCount() == 0 ? null : values.getAverage());
            summary.put(field + "Denominator", values.getCount());
        }
        summary.put(
            "errors",
            results
                .stream()
                .filter(row -> row.containsKey("errorCode"))
                .count()
        );
        summary.put(
            "permissionCases",
            results
                .stream()
                .filter(row -> row.containsKey("permissionPassed"))
                .count()
        );
        summary.put(
            "permissionPassed",
            results
                .stream()
                .filter(row -> Boolean.TRUE.equals(row.get("permissionPassed")))
                .count()
        );
        if (metrics.isEmpty()) {
            summary.put("hitRate", null);
            summary.put("recall", null);
            summary.put("mrr", null);
        }
        return summary;
    }

    /** 应用退出中断本次任务，未完成的持久任务由租约恢复器标记为失败。 */
    @PreDestroy
    public void shutdown() {
        runs.shutdownNow();
        cases.shutdownNow();
    }
}
