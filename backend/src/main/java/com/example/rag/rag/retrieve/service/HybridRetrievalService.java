package com.example.rag.rag.retrieve.service;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.example.rag.rag.retrieve.config.RetrievalProperties.*;
import com.example.rag.rag.retrieve.domain.RetrievalCandidate;
import com.example.rag.rag.retrieve.domain.RetrievalStatus;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;

/** 两路独立并行召回，故障只能回退到同一授权范围内的成功路线。 */
@Service
public class HybridRetrievalService {

    public record Route<T>(List<T> hits, String status, long elapsedMs) {}

    public record Result(
        List<RetrievalCandidate> fused,
        List<RetrievalCandidate> ranked,
        List<RetrievalCandidate> selected,
        String vectorStatus,
        String keywordStatus,
        String rerankStatus,
        long vectorMs,
        long keywordMs,
        long rerankMs
    ) {}

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);
    private final VectorSearchRepository vectors;
    private final KeywordRetriever keywords;
    private final EmbeddingGateway embedding;
    private final RerankerGateway reranker;
    private final RetrievalProperties config;
    private final ChatProperties chat;
    private final RrfFusion fusion;
    private final ParentSelectionPolicy parents;
    private final ThreadPoolExecutor executor;
    private final Scheduler scheduler;

    /** 建立有界召回线程池，避免超时路线持续占用无界资源。 */
    public HybridRetrievalService(
        VectorSearchRepository vectors,
        KeywordRetriever keywords,
        EmbeddingGateway embedding,
        RerankerGateway reranker,
        RetrievalProperties config,
        ChatProperties chat,
        RrfFusion fusion,
        ParentSelectionPolicy parents
    ) {
        this.vectors = vectors;
        this.keywords = keywords;
        this.embedding = embedding;
        this.reranker = reranker;
        this.config = config;
        this.chat = chat;
        this.fusion = fusion;
        this.parents = parents;
        executor = new ThreadPoolExecutor(
            config.workers(),
            config.workers(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(config.queueCapacity()),
            task -> {
                Thread thread = new Thread(task, "rag-retrieval");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        scheduler = Schedulers.fromExecutorService(executor);
    }

    /** 使用配置复杂度检索，供手动诊断与兼容调用使用。 */
    public Result retrieve(RetrievalScope scope, String question) {
        return retrieve(scope, question, config.defaultComplexity());
    }

    /** 两路各自应用超时与分数门槛，精排失败保留原始 RRF 排序及故障追踪。 */
    public Result retrieve(RetrievalScope scope, String question, Complexity complexity) {
        return retrieve(scope, question, question, complexity, null);
    }

    /** 独立传入 HyDE 向量查询和真实词项查询，总预算超时会取消两路上游。 */
    public Result retrieve(
        RetrievalScope scope,
        String question,
        String vectorQuery,
        Complexity complexity,
        Duration totalTimeout
    ) {
        return retrieveExperiment(scope, question, vectorQuery, complexity, totalTimeout, true, true);
    }

    /** 离线实验复用完全相同的授权召回和预算，仅显式控制精排开关以形成公平基线。 */
    public Result retrieveExperiment(
        RetrievalScope scope,
        String question,
        String vectorQuery,
        Complexity complexity,
        Duration totalTimeout,
        boolean keywordEnabled,
        boolean rerankEnabled
    ) {
        Profile profile = config.profile(complexity);
        Mono<Route<VectorSearchRepository.Hit>> vectorRoute = route(
            () -> vector(scope, vectorQuery, profile.vectorTopK()),
            config.vectorTimeout()
        );
        Mono<Route<KeywordRetriever.KeywordHit>> keywordRoute =
            !keywordEnabled || complexity == Complexity.SIMPLE
                ? Mono.just(new Route<>(List.of(), RetrievalStatus.SKIPPED, 0))
                : route(() -> keywords.search(scope, question, profile.keywordTopK()), config.keywordTimeout());
        // zip 同时订阅两路，各自超时从提交开始计时，不串行叠加预算。
        Mono<Result> result = Mono.zip(vectorRoute, keywordRoute).map(routes ->
            finish(question, complexity, profile, routes.getT1(), routes.getT2(), rerankEnabled)
        );
        return totalTimeout == null ? result.block() : result.block(totalTimeout);
    }

    /** 在 Embedding 后召回向量，并保留满足既有证据门槛的原始分数。 */
    private List<VectorSearchRepository.Hit> vector(RetrievalScope scope, String question, int topK) {
        List<List<Float>> query = embedding.embed(List.of(question));
        return vectors
            .search(scope, query.getFirst(), topK)
            .stream()
            .filter(hit -> 1 - hit.distance() >= chat.minSimilarity())
            .toList();
    }

    /** 传播 requestId/traceId，独立限制路线时长并脱敏记录失败类别。 */
    private <T> Mono<Route<T>> route(Supplier<List<T>> search, Duration timeout) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        long started = System.nanoTime();
        return Mono.fromCallable(() -> runSearch(search, context, started))
            .subscribeOn(scheduler)
            .timeout(timeout)
            .onErrorResume(failure ->
                Mono.just(
                    new Route<>(
                        List.of(),
                        failure instanceof TimeoutException ? RetrievalStatus.TIMEOUT : RetrievalStatus.UNAVAILABLE,
                        elapsed(started)
                    )
                )
            );
    }

    /** 在召回工作线程中恢复追踪上下文，结束后清除线程遗留状态。 */
    private <T> Route<T> runSearch(Supplier<List<T>> search, Map<String, String> context, long started) {
        if (context != null) {
            MDC.setContextMap(context);
        }
        try {
            return new Route<>(search.get(), RetrievalStatus.SUCCESS, elapsed(started));
        } finally {
            MDC.clear();
        }
    }

    /** 融合可用候选，校验精排完整性，并按 Parent 分布计算实际 Top-K。 */
    private Result finish(
        String question,
        Complexity complexity,
        Profile profile,
        Route<VectorSearchRepository.Hit> vector,
        Route<KeywordRetriever.KeywordHit> keyword,
        boolean rerankEnabled
    ) {
        if (!RetrievalStatus.SUCCESS.equals(vector.status()) && !RetrievalStatus.SUCCESS.equals(keyword.status())) {
            throw new BusinessException(ErrorCode.RAG_503_RETRIEVAL_UNAVAILABLE);
        }
        List<RetrievalCandidate> fused = fusion.fuse(
            vector.hits(),
            keyword.hits(),
            config.rrfK(),
            profile.fusionTopK()
        );
        List<RetrievalCandidate> ranked = fused;
        String rerankStatus = RetrievalStatus.SKIPPED;
        long started = System.nanoTime();
        if (rerankEnabled && complexity != Complexity.SIMPLE && !fused.isEmpty()) {
            try {
                ranked = rerank(question, fused);
                rerankStatus = RetrievalStatus.SUCCESS;
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw failure;
                }
                rerankStatus = RetrievalStatus.FALLBACK_RRF;
            }
        }
        long rerankMs = elapsed(started);
        List<RetrievalCandidate> selected = parents.select(ranked, profile.finalTopK());
        log.info(
            "retrieval complete, vectorStatus={}, keywordStatus={}, rerankStatus={}, fused={}, parents={}, " +
                "vectorMs={}, keywordMs={}, rerankMs={}",
            vector.status(),
            keyword.status(),
            rerankStatus,
            fused.size(),
            selected.size(),
            vector.elapsedMs(),
            keyword.elapsedMs(),
            rerankMs
        );
        return new Result(
            fused,
            ranked,
            selected,
            vector.status(),
            keyword.status(),
            rerankStatus,
            vector.elapsedMs(),
            keyword.elapsedMs(),
            rerankMs
        );
    }

    /** 防御性校验 Gateway 返回值，测试替身或后续供应商同样不得越界或重复索引。 */
    private List<RetrievalCandidate> rerank(String question, List<RetrievalCandidate> fused) {
        List<RerankScore> scores = reranker.rerank(
            question,
            fused
                .stream()
                .map(candidate -> candidate.hit().content())
                .toList()
        );
        if (scores == null || scores.isEmpty() || scores.size() > fused.size()) {
            throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
        }
        Set<Integer> indexes = new HashSet<>();
        List<RetrievalCandidate> ranked = new ArrayList<>();
        for (RerankScore score : scores) {
            if (
                score.candidateIndex() < 0 ||
                score.candidateIndex() >= fused.size() ||
                !indexes.add(score.candidateIndex()) ||
                !Double.isFinite(score.score()) ||
                score.score() < 0 ||
                score.score() > 1
            ) {
                throw new BusinessException(ErrorCode.RAG_503_RERANK_UNAVAILABLE);
            }
            ranked.add(fused.get(score.candidateIndex()).reranked(score.score()));
        }
        return ranked
            .stream()
            .sorted(
                Comparator.comparingDouble((RetrievalCandidate candidate) -> candidate.rerankScore())
                    .reversed()
                    .thenComparingDouble(candidate -> -candidate.rrfScore())
                    .thenComparingLong(candidate -> candidate.hit().chunkId())
            )
            .toList();
    }

    /** 将单调时钟差值换算为毫秒，仅用于实际调用追踪。 */
    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /** 应用停止时取消本模块的召回任务并释放线程资源。 */
    @PreDestroy
    public void shutdown() {
        scheduler.dispose();
        executor.shutdownNow();
    }
}
