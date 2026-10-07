package com.example.rag.rag.adaptive.service;

import com.example.rag.common.security.RetrievalScope;
import com.example.rag.conversation.domain.ConversationProtocol;
import com.example.rag.conversation.mapper.ConversationMapper;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.rag.adaptive.config.AdaptiveProperties;
import com.example.rag.rag.adaptive.domain.AdaptiveProtocol;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.domain.ModelStage;
import com.example.rag.rag.prompt.service.PromptCatalog;
import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.example.rag.rag.retrieve.config.RetrievalProperties.Complexity;
import com.example.rag.rag.retrieve.domain.RetrievalCandidate;
import com.example.rag.rag.retrieve.service.HybridRetrievalService;
import com.example.rag.rag.retrieve.service.ParentSelectionPolicy;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.stereotype.Service;

/** 所有子查询复用请求授权范围，HyDE 只传递给向量查询而不进入证据上下文。 */
@Service
public class AdaptiveRetrievalService {

    public record Query(String question, String vectorQuery) {}

    public record Plan(String query, Complexity route, String reasonCode, String fallback, List<Query> queries) {}

    public record Outcome(
        Plan plan,
        List<RetrievalCandidate> selected,
        List<HybridRetrievalService.Result> searches,
        long planningMs,
        long retrievalMs
    ) {}

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRetrievalService.class);
    private final StructuredModelService models;
    private final ConversationMapper messages;
    private final HybridRetrievalService retrieval;
    private final ChunkTokenizer tokenizer;
    private final AdaptiveProperties config;
    private final ChatProperties chat;
    private final RetrievalProperties budgets;
    private final ParentSelectionPolicy parents;
    private final ObjectMapper json;
    private final PromptCatalog prompts;
    private static final int MESSAGES_PER_ROUND = 2;

    /** 配置历史预算、模型规划和既有授权检索，不提供无范围召回入口。 */
    public AdaptiveRetrievalService(
        StructuredModelService models,
        ConversationMapper messages,
        HybridRetrievalService retrieval,
        ChunkTokenizer tokenizer,
        AdaptiveProperties config,
        ChatProperties chat,
        RetrievalProperties budgets,
        ParentSelectionPolicy parents,
        ObjectMapper json,
        PromptCatalog prompts
    ) {
        this.models = models;
        this.messages = messages;
        this.retrieval = retrieval;
        this.tokenizer = tokenizer;
        this.config = config;
        this.chat = chat;
        this.budgets = budgets;
        this.parents = parents;
        this.json = json;
        this.prompts = prompts;
    }

    /** 规划后按同一授权范围检索；复杂路线故障仅回退该范围的 STANDARD。 */
    public Outcome retrieve(RetrievalScope scope, long conversationId, String requestId, String question) {
        long started = System.nanoTime();
        Plan plan = plan(scope, conversationId, requestId, question);
        long planningMs = elapsed(started);
        long searchStarted = System.nanoTime();
        List<HybridRetrievalService.Result> searches = new ArrayList<>();
        try {
            for (Query query : plan.queries()) {
                Duration remaining = config.retrievalTimeout().minusNanos(System.nanoTime() - searchStarted);
                if (!remaining.isPositive()) {
                    throw new IllegalStateException("Adaptive retrieval budget exceeded");
                }
                searches.add(retrieval.retrieve(scope, query.question(), query.vectorQuery(), plan.route(), remaining));
            }
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted() || plan.route() != Complexity.COMPLEX) {
                throw failure;
            }
            // 复杂路线失败丢弃部分聚合，退回独立的原 Rewrite 查询，避免混入未完整验证的路线。
            plan = standard(plan.query(), AdaptiveProtocol.COMPLEX_RETRIEVAL_FAILED);
            searches.clear();
            Duration remaining = config.retrievalTimeout().minusNanos(System.nanoTime() - searchStarted);
            if (!remaining.isPositive()) {
                throw failure;
            }
            searches.add(retrieval.retrieve(scope, plan.query(), plan.query(), Complexity.STANDARD, remaining));
        }
        List<RetrievalCandidate> candidates = merge(searches, plan.route());
        log.info(
            "adaptive retrieval, route={}, reasonCode={}, fallback={}, queries={}, planningMs={}, retrievalMs={}",
            plan.route(),
            plan.reasonCode(),
            plan.fallback(),
            plan.queries().size(),
            planningMs,
            elapsed(searchStarted)
        );
        return new Outcome(plan, candidates, List.copyOf(searches), planningMs, elapsed(searchStarted));
    }

    /** Rewrite 仅读取本人已完成且知识库范围仍有效的最近轮次，解析失败保留原问题。 */
    public Plan plan(RetrievalScope scope, long conversationId, String requestId, String question) {
        if (!config.enabled()) {
            return new Plan(
                question,
                budgets.defaultComplexity(),
                AdaptiveProtocol.CONFIGURED_ROUTE,
                AdaptiveProtocol.DISABLED,
                List.of(new Query(question, question))
            );
        }
        Budget budget = new Budget();
        String query = question;
        String fallback = AdaptiveProtocol.NONE;
        try {
            String history = history(scope, conversationId, requestId);
            if (!history.isBlank()) {
                JsonNode rewritten = budget.call(
                    ModelStage.REWRITE.name(),
                    prompts.rewrite(),
                    json.writeValueAsString(
                        Map.of(AdaptiveProtocol.HISTORY, history, AdaptiveProtocol.QUESTION, question)
                    )
                );
                query = text(rewritten, AdaptiveProtocol.QUERY, chat.questionTokens());
            }
        } catch (Exception failure) {
            propagateInterruption(failure);
            fallback = AdaptiveProtocol.REWRITE_FAILED;
        }
        Complexity route;
        try {
            JsonNode decision = budget.call(ModelStage.ROUTE.name(), prompts.router(), query);
            route = Complexity.valueOf(decision.path(AdaptiveProtocol.ROUTE).textValue());
        } catch (RuntimeException failure) {
            propagateInterruption(failure);
            return standard(
                query,
                AdaptiveProtocol.ROUTER_FAILED + (AdaptiveProtocol.NONE.equals(fallback) ? "" : ":" + fallback)
            );
        }
        if (route != Complexity.COMPLEX) {
            return new Plan(
                query,
                route,
                AdaptiveProtocol.MODEL_REASON_PREFIX + route,
                fallback,
                List.of(new Query(query, query))
            );
        }
        try {
            JsonNode decomposition = budget.call(
                ModelStage.DECOMPOSE.name(),
                prompts.decompose(config.maxSubqueries()),
                query
            );
            JsonNode nodes = decomposition.path(AdaptiveProtocol.QUERIES);
            if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > config.maxSubqueries()) {
                throw new IllegalArgumentException("Invalid subquery count");
            }
            LinkedHashSet<String> questions = new LinkedHashSet<>();
            for (JsonNode node : nodes) {
                questions.add(validText(node, chat.questionTokens()));
            }
            JsonNode hypothetical = budget.call(ModelStage.HYDE.name(), prompts.hyde(), query);
            String passage = text(hypothetical, AdaptiveProtocol.PASSAGE, chat.questionTokens());
            List<Query> queries = new ArrayList<>();
            queries.add(new Query(query, passage));
            for (String subquestion : questions) {
                if (!subquestion.equals(query)) {
                    queries.add(new Query(subquestion, subquestion));
                }
            }
            return new Plan(
                query,
                route,
                AdaptiveProtocol.MODEL_REASON_PREFIX + Complexity.COMPLEX,
                fallback,
                List.copyOf(queries)
            );
        } catch (RuntimeException failure) {
            propagateInterruption(failure);
            return standard(query, AdaptiveProtocol.COMPLEX_PLAN_FAILED);
        }
    }

    /** 以统一融合分数跨查询累积 Parent 支持，单查询保持既有排名与分布规则。 */
    private List<RetrievalCandidate> merge(List<HybridRetrievalService.Result> searches, Complexity route) {
        if (searches.size() == 1) {
            return searches.getFirst().selected();
        }
        Map<Long, RetrievalCandidate> candidates = new LinkedHashMap<>();
        Map<Long, Double> support = new HashMap<>();
        for (HybridRetrievalService.Result search : searches) {
            int rank = 0;
            for (RetrievalCandidate candidate : search.ranked()) {
                long parentId = candidate.hit().parentChunkId();
                candidates.putIfAbsent(parentId, candidate);
                support.merge(parentId, 1.0 / (budgets.rrfK() + ++rank), Double::sum);
            }
        }
        List<RetrievalCandidate> ranked = candidates
            .entrySet()
            .stream()
            .sorted(
                Comparator.<Map.Entry<Long, RetrievalCandidate>>comparingDouble(
                    entry -> -support.get(entry.getKey())
                ).thenComparingLong(Map.Entry::getKey)
            )
            .map(entry -> {
                RetrievalCandidate candidate = entry.getValue();
                return new RetrievalCandidate(
                    candidate.hit(),
                    candidate.vectorRank(),
                    candidate.vectorScore(),
                    candidate.keywordRank(),
                    candidate.keywordScore(),
                    support.get(entry.getKey()),
                    null
                );
            })
            .toList();
        return parents.select(ranked, budgets.profile(route).finalTopK());
    }

    /** 保留最近完整轮次并从新到旧应用 Token 上限，知识库收缩后排除旧范围。 */
    private String history(RetrievalScope scope, long conversationId, String requestId) throws Exception {
        List<Long> ids = scope
            .knowledgeBases()
            .stream()
            .map(RetrievalScope.KnowledgeBaseDepartment::knowledgeBaseId)
            .toList();
        List<ConversationMapper.History> recent = messages.history(
            conversationId,
            scope.userId(),
            requestId,
            json.writeValueAsString(ids),
            config.historyRounds() * MESSAGES_PER_ROUND
        );
        List<String> history = new ArrayList<>();
        int remaining = config.historyTokens();
        for (int index = 0; index + 1 < recent.size(); index += MESSAGES_PER_ROUND) {
            ConversationMapper.History assistant = recent.get(index);
            ConversationMapper.History user = recent.get(index + 1);
            if (
                !ConversationProtocol.ASSISTANT.equals(assistant.role()) ||
                !ConversationProtocol.USER.equals(user.role())
            ) {
                continue;
            }
            String line =
                ConversationProtocol.USER +
                ": " +
                user.content() +
                "\n" +
                ConversationProtocol.ASSISTANT +
                ": " +
                assistant.content();
            int tokens = tokenizer.count(line);
            if (tokens > remaining) {
                break;
            }
            history.add(line);
            remaining -= tokens;
        }
        Collections.reverse(history);
        return String.join("\n", history);
    }

    /** 构造有稳定原因码的 STANDARD 回退，不扩展授权范围。 */
    private Plan standard(String question, String fallback) {
        return new Plan(
            question,
            Complexity.STANDARD,
            AdaptiveProtocol.FALLBACK_STANDARD,
            fallback,
            List.of(new Query(question, question))
        );
    }

    /** 要求模型输出为非空文本且处于配置 Token 预算内。 */
    private String text(JsonNode node, String field, int tokens) {
        return validText(node.path(field), tokens);
    }

    /** 禁止模型结构值、空串和超预算查询进入召回。 */
    private String validText(JsonNode node, int tokens) {
        if (!node.isTextual() || node.textValue().isBlank() || tokenizer.count(node.textValue()) > tokens) {
            throw new IllegalArgumentException("Invalid model query");
        }
        return node.textValue().trim();
    }

    /** 请求取消时立即传播中断，不能通过模型失败回退继续产生费用。 */
    private void propagateInterruption(Exception failure) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Planning interrupted", failure);
        }
    }

    /** 用单调时钟记录阶段延迟。 */
    private long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private final class Budget {

        private final long started = System.nanoTime();
        private int calls;

        /** 共享规划总预算并限制实际模型调用数，任何超限触发规定回退。 */
        private JsonNode call(String stage, String instruction, String input) {
            Duration remaining = config.planningTimeout().minusNanos(System.nanoTime() - started);
            if (++calls > config.maxModelCalls() || !remaining.isPositive()) {
                throw new IllegalStateException("Planning budget exceeded");
            }
            Duration timeout = remaining.compareTo(config.callTimeout()) < 0 ? remaining : config.callTimeout();
            return models.call(stage, instruction, input, config.outputTokens(), timeout);
        }
    }
}
