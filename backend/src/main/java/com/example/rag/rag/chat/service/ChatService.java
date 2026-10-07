package com.example.rag.rag.chat.service;

import com.example.rag.common.api.SseEventType;
import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.conversation.domain.ConversationProtocol;
import com.example.rag.conversation.dto.ConversationModels.Citation;
import com.example.rag.conversation.mapper.ConversationMapper;
import com.example.rag.conversation.support.CitationMarkerFilter;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.governance.domain.GovernanceProtocol;
import com.example.rag.governance.service.RedisCostGuard;
import com.example.rag.governance.service.SemanticAnswerCache;
import com.example.rag.knowledgebase.domain.Permission;
import com.example.rag.rag.adaptive.service.AdaptiveRetrievalService;
import com.example.rag.rag.chat.config.ChatPersistenceProperties;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.chat.dto.ChatRequest;
import com.example.rag.rag.faithfulness.domain.VerificationProtocol;
import com.example.rag.rag.faithfulness.service.EvidenceJudge;
import com.example.rag.rag.faithfulness.service.EvidenceJudge.Verification;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.rag.retrieve.config.RetrievalProperties.Complexity;
import com.example.rag.usage.domain.UsageProtocol;
import com.example.rag.usage.repository.UsageRepository;
import com.example.rag.user.domain.UserAccount;
import com.example.rag.user.service.UserApplicationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;

/** 管理流式会话的生命周期；模型调用和网络 IO 均在数据库事务之外执行。 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ConversationMapper messages;
    private final PermissionScopeService permissions;
    private final UserApplicationService users;
    private final GenerationGateway generation;
    private final AdaptiveRetrievalService adaptive;
    private final EvidenceJudge judge;
    private final UsageRepository usage;
    private final ContextBuilder contexts;
    private final ChunkTokenizer tokenizer;
    private final ChatProperties config;
    private final ChatPersistenceProperties persistence;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final RedisCostGuard guard;
    private final SemanticAnswerCache cache;
    private final Semaphore permits;
    private final ExecutorService workers;
    private final ScheduledExecutorService timers;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    /** 配置聊天授权、检索、生成与持久化依赖，建立并发许可和事务边界。 */
    public ChatService(
        ConversationMapper messages,
        PermissionScopeService permissions,
        UserApplicationService users,
        GenerationGateway generation,
        ContextBuilder contexts,
        ChunkTokenizer tokenizer,
        ChatProperties config,
        ObjectMapper json,
        PlatformTransactionManager manager,
        AdaptiveRetrievalService adaptive,
        EvidenceJudge judge,
        UsageRepository usage,
        ChatPersistenceProperties persistence,
        RedisCostGuard guard,
        SemanticAnswerCache cache
    ) {
        this.messages = messages;
        this.permissions = permissions;
        this.users = users;
        this.generation = generation;
        this.adaptive = adaptive;
        this.judge = judge;
        this.usage = usage;
        this.contexts = contexts;
        this.tokenizer = tokenizer;
        this.config = config;
        this.persistence = persistence;
        this.json = json;
        this.guard = guard;
        this.cache = cache;
        transactions = new TransactionTemplate(manager);
        permits = new Semaphore(config.maxConcurrency());
        workers = Executors.newFixedThreadPool(config.maxConcurrency(), task -> {
            Thread thread = new Thread(task, "rag-chat");
            thread.setDaemon(true);
            return thread;
        });
        timers = Executors.newScheduledThreadPool(1, task -> {
            Thread thread = new Thread(task, "rag-chat-timer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 先校验请求与授权范围，再创建消息对、会话资源和 SSE 流。 */
    public SseEmitter open(ChatRequest request, String requestId, String traceId) {
        UserAccount actor = permissions.currentUser();
        RetrievalScope scope = permissions.resolve(request.knowledgeBaseIds());
        if (tokenizer.count(request.message()) > config.questionTokens()) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        guard.request(actor);
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.RAG_429_BUSY);
        }
        Session session;
        try {
            long messageId = transactions.execute(transaction ->
                createRequestMessages(request, actor, requestId, traceId)
            );
            session = new Session(request, scope, messageId, requestId, traceId);
            sessions.put(requestId, session);
        } catch (RuntimeException failure) {
            permits.release();
            throw failure;
        }
        session.emitter.onCompletion(() -> session.end(ConversationProtocol.CANCELLED, null, false));
        session.emitter.onError(failure -> session.end(ConversationProtocol.CANCELLED, null, false));
        session.emitter.onTimeout(() -> session.end(ConversationProtocol.FAILED, ErrorCode.RAG_504_TIMEOUT, true));
        session.start();
        return session.emitter;
    }

    /** 在短事务内锁定会话并建立唯一的用户消息与生成占位消息。 */
    private long createRequestMessages(ChatRequest request, UserAccount actor, String requestId, String traceId) {
        if (messages.lock(request.conversationId(), actor.id()) == null) {
            throw new BusinessException(ErrorCode.CHAT_404_NOT_FOUND);
        }
        messages.expire(request.conversationId());
        if (messages.busy(request.conversationId())) {
            throw new BusinessException(ErrorCode.CHAT_409_BUSY);
        }
        String scopeJson = encode(request.knowledgeBaseIds());
        messages.insert(
            request.conversationId(),
            actor.id(),
            ConversationProtocol.USER,
            request.message(),
            requestId,
            traceId,
            ConversationProtocol.COMPLETED,
            scopeJson,
            config.timeout().toMillis()
        );
        long assistantMessageId = messages.insert(
            request.conversationId(),
            actor.id(),
            ConversationProtocol.ASSISTANT,
            "",
            requestId,
            traceId,
            ConversationProtocol.GENERATING,
            scopeJson,
            config.timeout().toMillis()
        );
        messages.touch(request.conversationId());
        usage.startRequest(requestId, traceId, assistantMessageId, actor.id(), actor.departmentId());
        return assistantMessageId;
    }

    /** 只允许请求所属用户取消生成，兼容已结束请求。 */
    public void cancel(String requestId) {
        long userId = permissions.currentUser().id();
        Long ownerUserId = messages.requestOwner(requestId);
        if (ownerUserId == null || ownerUserId != userId) {
            throw new BusinessException(ErrorCode.CHAT_404_NOT_FOUND);
        }
        Session session = sessions.get(requestId);
        if (session != null) {
            session.end(ConversationProtocol.CANCELLED, null, true);
        } else {
            messages.cancelRequest(requestId);
        }
    }

    /** 将引用快照序列化为 JSON，失败时转换为业务错误。 */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode chat data", failure);
        }
    }

    /** 重新计算请求知识库的授权范围，避免继续使用已撤销权限。 */
    private void authorize(RetrievalScope scope) {
        UserAccount actor = users.requireActive(scope.userId());
        for (RetrievalScope.KnowledgeBaseDepartment pair : scope.knowledgeBases()) {
            permissions.require(pair.knowledgeBaseId(), actor, Permission.READ, false);
        }
    }

    /** 应用关闭时结束活动会话并释放调度资源。 */
    @PreDestroy
    public void shutdown() {
        for (Session session : sessions.values()) {
            session.end(ConversationProtocol.CANCELLED, null, false);
        }
        workers.shutdownNow();
        timers.shutdownNow();
    }

    private final class Session {

        private final ChatRequest request;
        private final RetrievalScope scope;
        private final long messageId;
        private final String requestId;
        private final String traceId;
        private final SseEmitter emitter = new SseEmitter(config.timeout().toMillis());
        private final StringBuilder answer = new StringBuilder();
        private final Disposable.Swap upstream = Disposables.swap();
        private List<Citation> citations = List.of();
        private Map<String, ContextBuilder.Evidence> evidence = Map.of();
        private CitationMarkerFilter markers = new CitationMarkerFilter(Set.of());
        private boolean terminal;
        private int sequence;
        private Long inputTokens;
        private Long outputTokens;
        private Future<?> worker;
        private ScheduledFuture<?> heartbeat;
        private ScheduledFuture<?> deadline;
        private long lastSaved;
        private final long started = System.nanoTime();
        private Long firstTokenMs;
        private AdaptiveRetrievalService.Outcome retrieved;
        private Verification verification = judge.unknown(VerificationProtocol.PENDING);
        private String standaloneQuestion;
        private String route = Complexity.STANDARD.name();
        private String reasonCode = VerificationProtocol.PENDING;
        private SemanticAnswerCache.Lookup cacheLookup;
        private boolean cacheHit;

        /** 保存一次流式请求的授权范围、消息 ID 和追踪信息。 */
        private Session(ChatRequest request, RetrievalScope scope, long messageId, String requestId, String traceId) {
            this.request = request;
            this.scope = scope;
            this.messageId = messageId;
            this.requestId = requestId;
            this.traceId = traceId;
        }

        /** 启动检索与生成任务并安排心跳及总超时。 */
        private synchronized void start() {
            heartbeat = timers.scheduleAtFixedRate(
                this::beat,
                config.heartbeat().toMillis(),
                config.heartbeat().toMillis(),
                TimeUnit.MILLISECONDS
            );
            deadline = timers.schedule(
                () -> end(ConversationProtocol.FAILED, ErrorCode.RAG_504_TIMEOUT, true),
                config.timeout().toMillis(),
                TimeUnit.MILLISECONDS
            );
            worker = workers.submit(this::retrieve);
        }

        /** 重新校验授权后检索证据、构建上下文并启动生成，无证据时保守结束。 */
        private void retrieve() {
            try (
                MDC.MDCCloseable requestContext = MDC.putCloseable(TraceContextKeys.REQUEST_ID, requestId);
                MDC.MDCCloseable traceContext = MDC.putCloseable(TraceContextKeys.TRACE_ID, traceId)
            ) {
                status(ConversationProtocol.RETRIEVING, "正在检索知识库");
                if (closed()) {
                    return;
                }
                cacheLookup = cache.lookup(scope, request.conversationId(), request.message());
                if (cacheLookup != null && cacheLookup.entry() != null) {
                    useCache(cacheLookup.entry());
                    return;
                }
                retrieved = adaptive.retrieve(scope, request.conversationId(), requestId, request.message());
                standaloneQuestion = retrieved.plan().query();
                route = retrieved.plan().route().name();
                reasonCode = retrieved.plan().reasonCode();
                if (closed()) {
                    return;
                }
                ContextBuilder.Context context = contexts.buildCandidates(standaloneQuestion, retrieved.selected());
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    authorize(scope);
                    citations = context.citations();
                    evidence = context.evidence();
                    markers = new CitationMarkerFilter(
                        citations.stream().map(Citation::citationId).collect(java.util.stream.Collectors.toSet())
                    );
                    for (Citation citation : citations) {
                        emit(SseEventType.CITATION, citation);
                    }
                }
                if (context.citations().isEmpty()) {
                    accept(
                        new GenerationDelta(
                            "当前所选知识库中没有足够证据，无法确认该问题。请补充相关文档或调整问题。",
                            true
                        )
                    );
                    verification = judge.verify(standaloneQuestion, answer.toString(), evidence);
                    end(ConversationProtocol.COMPLETED, null, true);
                    return;
                }
                status(ConversationProtocol.GENERATING, "正在生成回答");
                if (closed()) {
                    return;
                }
                upstream.update(
                    generation
                        .generate(context.request())
                        .subscribe(
                            this::accept,
                            failure -> end(ConversationProtocol.FAILED, code(failure), true),
                            this::scheduleVerification
                        )
                );
            } catch (RuntimeException failure) {
                end(ConversationProtocol.FAILED, code(failure), true);
            }
        }

        /** 缓存复用保持同一 SSE、历史和引用协议，输出前逐次校验当前权限与固定版本。 */
        private synchronized void useCache(SemanticAnswerCache.Entry entry) {
            if (terminal || !cache.valid(scope, entry)) {
                throw new BusinessException(ErrorCode.EVAL_409_SNAPSHOT_CHANGED);
            }
            cacheHit = true;
            citations = entry.citations();
            evidence = entry.evidence();
            verification = entry.verification();
            retrieved = entry.retrieval();
            route = retrieved.plan().route().name();
            reasonCode = GovernanceProtocol.CACHE_HIT;
            standaloneQuestion = request.message();
            markers = new CitationMarkerFilter(evidence.keySet());
            for (Citation citation : citations) {
                emit(SseEventType.CITATION, citation);
            }
            accept(new GenerationDelta(entry.answer(), true));
            end(ConversationProtocol.COMPLETED, null, true);
        }

        /** 流结束后调度 Judge，避免在流回调或会话锁内等待模型响应。 */
        private synchronized void scheduleVerification() {
            if (terminal) {
                return;
            }
            String remainder = markers.finish();
            if (!remainder.isEmpty()) {
                answer.append(remainder);
                emit(SseEventType.CONTENT, Map.of("delta", remainder));
            }
            status(ConversationProtocol.VERIFYING, "正在核验回答证据");
            worker = workers.submit(this::verifyAnswer);
        }

        /** 只核验公开回答和实际证据，迟到 Judge 结果不得覆盖取消或超时终态。 */
        private void verifyAnswer() {
            try (
                MDC.MDCCloseable requestContext = MDC.putCloseable(TraceContextKeys.REQUEST_ID, requestId);
                MDC.MDCCloseable traceContext = MDC.putCloseable(TraceContextKeys.TRACE_ID, traceId)
            ) {
                Verification judged = judge.verify(standaloneQuestion, answer.toString(), evidence);
                synchronized (this) {
                    if (terminal) {
                        return;
                    }
                    authorize(scope);
                    verification = judged;
                    if (!VerificationProtocol.SUPPORTED.equals(judged.status())) {
                        String warning =
                            "\n\n> 证据核验提示：" +
                            switch (judged.status()) {
                                case VerificationProtocol.UNSUPPORTED -> "当前回答缺少证据支持，请勿据此作出决定。";
                                case VerificationProtocol.PARTIALLY_SUPPORTED -> "当前回答仅获部分证据支持，请核对原文。";
                                default -> "本次核验未完成，回答尚未核实。";
                            };
                        accept(new GenerationDelta(warning, false));
                    }
                    end(ConversationProtocol.COMPLETED, null, true);
                }
            } catch (RuntimeException failure) {
                end(ConversationProtocol.FAILED, code(failure), true);
            }
        }

        /** 将阶段失败映射为允许对客户端公开的错误码。 */
        private ErrorCode code(Throwable failure) {
            if (failure instanceof BusinessException business) {
                return business.errorCode();
            }
            if (failure instanceof IndexFailure) {
                return ErrorCode.RAG_503_MODEL_UNAVAILABLE;
            }
            log.warn(
                "chat request failed, requestId={}, traceId={}, messageId={}, exceptionType={}",
                requestId,
                traceId,
                messageId,
                failure.getClass().getSimpleName()
            );
            return ErrorCode.RAG_503_MODEL_UNAVAILABLE;
        }

        /** 判断会话是否已进入终态，供异步输出丢弃迟到结果。 */
        private synchronized boolean closed() {
            return terminal;
        }

        /** 向客户端报告当前 RAG 阶段。 */
        private synchronized void status(String stage, String text) {
            emit(SseEventType.STATUS, Map.of("stage", stage, "message", text));
        }

        /** 接受模型公开增量与用量，过滤终态后的迟到输出。 */
        private synchronized void accept(GenerationDelta delta) {
            if (terminal) {
                return;
            }
            authorize(scope);
            if (delta.inputTokens() != null) {
                inputTokens = delta.inputTokens();
            }
            if (delta.outputTokens() != null) {
                outputTokens = delta.outputTokens();
            }
            if (delta.delta() == null || delta.delta().isEmpty()) {
                return;
            }
            if (firstTokenMs == null) {
                firstTokenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            }
            if (answer.length() + delta.delta().length() > config.maxOutputCharacters()) {
                throw new BusinessException(ErrorCode.RAG_503_MODEL_UNAVAILABLE);
            }
            String safeDelta = markers.append(delta.delta());
            if (answer.length() + safeDelta.length() > config.maxOutputCharacters()) {
                throw new BusinessException(ErrorCode.RAG_503_MODEL_UNAVAILABLE);
            }
            answer.append(safeDelta);
            // 部分回答按配置间隔保存，结束时始终保存最终快照。
            if (System.nanoTime() - lastSaved > persistence.progressInterval().toNanos()) {
                if (messages.progress(messageId, answer.toString()) == 0) {
                    end(ConversationProtocol.CANCELLED, null, false);
                    return;
                }
                lastSaved = System.nanoTime();
            }
            if (!safeDelta.isEmpty()) {
                emit(SseEventType.CONTENT, Map.of("delta", safeDelta));
            }
        }

        /** 发送常规 SSE 事件并处理连接中断。 */
        private synchronized void emit(SseEventType event, Object data) {
            if (terminal) {
                return;
            }
            authorize(scope);
            try {
                emitter.send(
                    SseEmitter.event()
                        .id(requestId + ":" + ++sequence)
                        .name(event.wireName())
                        .data(data)
                );
            } catch (IOException failure) {
                end(ConversationProtocol.CANCELLED, null, false);
            }
        }

        /** 发送心跳并复查授权，及时停止失效会话。 */
        private synchronized void beat() {
            if (terminal) {
                return;
            }
            try {
                authorize(scope);
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException failure) {
                end(ConversationProtocol.CANCELLED, null, false);
            } catch (RuntimeException failure) {
                end(ConversationProtocol.FAILED, code(failure), true);
            }
        }

        /** 以单一终态结束会话，保存部分回答并释放并发许可。 */
        private synchronized void end(String state, ErrorCode error, boolean writable) {
            if (terminal) {
                return;
            }
            String remainder = markers.finish();
            if (answer.length() + remainder.length() > config.maxOutputCharacters()) {
                state = ConversationProtocol.FAILED;
                error = ErrorCode.RAG_503_MODEL_UNAVAILABLE;
                remainder = "";
            }
            if (!remainder.isEmpty()) {
                answer.append(remainder);
                if (writable && ConversationProtocol.COMPLETED.equals(state)) {
                    emit(SseEventType.CONTENT, Map.of("delta", remainder));
                }
            }
            terminal = true;

            // 先终止计时器和上游生成，后续只处理最终快照与终态事件。
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
            upstream.dispose();
            if (worker != null && !worker.isDone()) {
                worker.cancel(true);
            }
            try (
                MDC.MDCCloseable requestContext = MDC.putCloseable(TraceContextKeys.REQUEST_ID, requestId);
                MDC.MDCCloseable traceContext = MDC.putCloseable(TraceContextKeys.TRACE_ID, traceId)
            ) {
                // 取消操作可能中断工作线程；暂时清除中断标记，让终态写入能够完成。
                boolean interrupted = Thread.interrupted();
                try {
                    if (ConversationProtocol.COMPLETED.equals(state)) {
                        try {
                            authorize(scope);
                        } catch (BusinessException revoked) {
                            state = ConversationProtocol.FAILED;
                            error = revoked.errorCode();
                        }
                    }
                    String finalState = state;
                    if (ConversationProtocol.COMPLETED.equals(state) && answer.isEmpty()) {
                        finalState = ConversationProtocol.FAILED;
                        error = ErrorCode.RAG_503_MODEL_UNAVAILABLE;
                    }
                    boolean saved = persistTerminalMessage(finalState, error);
                    if (
                        saved && ConversationProtocol.COMPLETED.equals(finalState) && !cacheHit && cacheLookup != null
                    ) {
                        cache.save(
                            scope,
                            cacheLookup,
                            new SemanticAnswerCache.Entry(
                                request.message(),
                                null,
                                answer.toString(),
                                citations,
                                evidence,
                                verification,
                                retrieved,
                                Long.MAX_VALUE
                            )
                        );
                    }
                    if (writable && saved) {
                        if (error != null) {
                            terminalEvent(
                                SseEventType.ERROR,
                                Map.of("code", error.name(), "message", error.message(), "requestId", requestId)
                            );
                        } else {
                            emitCompletionEvents(finalState);
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (RuntimeException failure) {
                // 最终提交失败时不发送 done；失联工作线程由 expires_at 过期机制恢复。
                log.error(
                    "chat terminal persistence failed, requestId={}, traceId={}, messageId={}, exceptionType={}",
                    requestId,
                    traceId,
                    messageId,
                    failure.getClass().getSimpleName()
                );
                if (writable) {
                    ErrorCode failureCode = code(failure);
                    terminalEvent(
                        SseEventType.ERROR,
                        Map.of("code", failureCode.name(), "message", failureCode.message(), "requestId", requestId)
                    );
                }
            } finally {
                sessions.remove(requestId, this);
                permits.release();
                emitter.complete();
            }
        }

        /** 在同一事务中保存消息终态与引用快照，避免完成事件先于持久化。 */
        private boolean persistTerminalMessage(String state, ErrorCode error) {
            // 回答终态与引用快照在同一短事务中保存，成功后才能发送完成事件。
            return Boolean.TRUE.equals(transactions.execute(transaction -> saveTerminal(state, error)));
        }

        /** 执行终态事务的消息、核验、追踪与引用写入，零更新时丢弃迟到结果。 */
        private boolean saveTerminal(String state, ErrorCode error) {
            int updatedMessages = messages.finish(
                messageId,
                answer.toString(),
                state,
                error == null ? null : error.name(),
                inputTokens,
                outputTokens
            );
            if (updatedMessages == 0) {
                return false;
            }
            saveCitations();
            if (!ConversationProtocol.COMPLETED.equals(state)) {
                verification = judge.unknown(state);
            }
            messages.verification(
                messageId,
                verification.confidenceLevel(),
                verification.status(),
                encode(verification),
                route,
                retrieved == null ? "{}" : encode(retrieved)
            );
            usage.finishRequest(
                requestId,
                route,
                reasonCode,
                state,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                firstTokenMs
            );
            usage.markCacheHit(requestId, cacheHit);
            messages.touch(request.conversationId());
            return true;
        }

        /** 在消息终态事务内保存引用预览与实际入模正文，确保历史来源一致。 */
        private void saveCitations() {
            for (Citation citation : citations) {
                messages.citation(messageId, citation, encode(citation.tables()), encode(citation.preview()));
                ContextBuilder.Evidence snapshot = evidence.get(citation.citationId());
                messages.citationEvidence(
                    messageId,
                    citation.citationId(),
                    snapshot.content(),
                    snapshot.metadataJson()
                );
            }
        }

        /** 依次发送核验、用量和完成事件，保持 SSE 协议顺序。 */
        private void emitCompletionEvents(String finalState) {
            terminalEvent(SseEventType.VERIFICATION, verification);

            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("inputTokens", inputTokens);
            usage.put("outputTokens", outputTokens);
            Map<String, Object> costs = ChatService.this.usage.requestCost(requestId);
            usage.put("estimatedCost", costs.get("cost"));
            usage.put("unpricedCalls", costs.get("unpriced_calls"));
            usage.put("calls", costs.get("calls"));
            usage.put("currency", UsageProtocol.CURRENCY);
            usage.put("cacheHit", cacheHit);
            terminalEvent(SseEventType.USAGE, usage);

            terminalEvent(
                SseEventType.DONE,
                Map.of("messageId", messageId, "requestId", requestId, "traceId", traceId, "finishReason", finalState)
            );
        }

        /** 发送终态事件，连接已关闭时仅记录追踪信息。 */
        private void terminalEvent(SseEventType event, Object data) {
            try {
                emitter.send(
                    SseEmitter.event()
                        .id(requestId + ":" + ++sequence)
                        .name(event.wireName())
                        .data(data)
                );
            } catch (IOException failure) {
                log.debug("chat connection closed, requestId={}", requestId);
            }
        }
    }
}
