package com.example.rag.governance.service;

import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.conversation.dto.ConversationModels.Citation;
import com.example.rag.governance.config.GovernanceProperties;
import com.example.rag.governance.mapper.GovernanceMapper;
import com.example.rag.rag.adaptive.service.AdaptiveRetrievalService;
import com.example.rag.rag.chat.service.ContextBuilder.Evidence;
import com.example.rag.rag.faithfulness.domain.VerificationProtocol;
import com.example.rag.rag.faithfulness.service.EvidenceJudge;
import com.example.rag.rag.model.gateway.ModelGateways.EmbeddingGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 缓存仅复用单轮且已核实答案；身份、ACL、版本、模型、预算和 Prompt 均参与失效边界。 */
@Service
public class SemanticAnswerCache {

    public record Entry(
        String question,
        List<Float> vector,
        String answer,
        List<Citation> citations,
        Map<String, Evidence> evidence,
        EvidenceJudge.Verification verification,
        AdaptiveRetrievalService.Outcome retrieval,
        long expiresAt
    ) {}

    public record Lookup(String key, String fingerprint, List<Float> vector, Entry entry) {}

    private static final Logger log = LoggerFactory.getLogger(SemanticAnswerCache.class);
    private static final Pattern PROTECTED_TERMS = Pattern.compile("[0-9]+(?:[.][0-9]+)?|不|未|无|禁止|不得|取消");
    private final StringRedisTemplate redis;
    private final ScopeFingerprint scopes;
    private final RagConfigurationSnapshot configuration;
    private final GovernanceProperties config;
    private final EmbeddingGateway embedding;
    private final PermissionScopeService permissions;
    private final GovernanceMapper mapper;
    private final ObjectMapper json;
    private final EvidenceJudge judge;
    private final String namespace;
    private final DefaultRedisScript<Long> store = new DefaultRedisScript<>();

    /** 原子限长写入，缓存内容保留 TTL 和来源快照；schema 隔离避免验收污染开发缓存。 */
    public SemanticAnswerCache(
        StringRedisTemplate redis,
        ScopeFingerprint scopes,
        RagConfigurationSnapshot configuration,
        GovernanceProperties config,
        EmbeddingGateway embedding,
        PermissionScopeService permissions,
        GovernanceMapper mapper,
        ObjectMapper json,
        EvidenceJudge judge
    ) {
        this.redis = redis;
        this.scopes = scopes;
        this.configuration = configuration;
        this.config = config;
        this.embedding = embedding;
        this.permissions = permissions;
        this.mapper = mapper;
        this.json = json;
        this.judge = judge;
        namespace = ScopeFingerprint.digest(mapper.namespace());
        store.setLocation(new ClassPathResource("lua/cache-store.lua"));
        store.setResultType(Long.class);
    }

    /** 有历史的请求走 Rewrite，不能把单轮缓存当作多轮问题答案。 */
    public Lookup lookup(RetrievalScope scope, long conversationId, String question) {
        if (!config.cacheEnabled()) {
            return null;
        }
        Integer completed = mapper.completedAnswers(conversationId);
        if (completed > 0) {
            return null;
        }
        String fingerprint = scopes.current(scope);
        String key =
            "rag:semantic:" + namespace + ":" + scope.userId() + ":" + fingerprint + ":" + configuration.digest();
        List<Entry> entries = read(key);
        for (Entry entry : entries) {
            if (entry.question().strip().equals(question.strip()) && valid(scope, entry)) {
                return new Lookup(key, fingerprint, null, entry);
            }
        }
        // 非精确问题使用 Embedding 判断相似度，未命中时同一向量随答案写入缓存，所有调用均治理和计量。
        List<Float> vector = embedding.embed(List.of(question)).getFirst();
        for (Entry entry : entries) {
            if (
                protectedTerms(question).equals(protectedTerms(entry.question())) &&
                cosine(vector, entry.vector()) >= config.cacheSimilarity() &&
                valid(scope, entry)
            ) {
                EvidenceJudge.Verification verification = judge.verify(question, entry.answer(), entry.evidence());
                if (VerificationProtocol.SUPPORTED.equals(verification.status())) {
                    Entry verified = new Entry(
                        question,
                        entry.vector(),
                        entry.answer(),
                        entry.citations(),
                        entry.evidence(),
                        verification,
                        entry.retrieval(),
                        entry.expiresAt()
                    );
                    return new Lookup(key, fingerprint, vector, verified);
                }
            }
        }
        return new Lookup(key, fingerprint, vector, null);
    }

    /** 终态提交后才写缓存，取消、无证据、部分支持和核验故障均不能被复用。 */
    public void save(RetrievalScope scope, Lookup lookup, Entry entry) {
        if (
            lookup == null ||
            lookup.entry() != null ||
            !VerificationProtocol.SUPPORTED.equals(entry.verification().status()) ||
            entry.citations().isEmpty() ||
            entry.answer().length() > config.cacheMaxCharacters()
        ) {
            return;
        }
        if (!lookup.fingerprint().equals(scopes.current(scope)) || !valid(scope, entry)) {
            return;
        }
        try {
            Entry stored = new Entry(
                entry.question(),
                lookup.vector(),
                entry.answer(),
                entry.citations(),
                entry.evidence(),
                entry.verification(),
                entry.retrieval(),
                System.currentTimeMillis() + config.cacheTtl().toMillis()
            );
            String value = json.writeValueAsString(stored);
            if (value.length() > config.cacheMaxCharacters()) {
                return;
            }
            redis.execute(
                store,
                List.of(lookup.key()),
                value,
                Integer.toString(config.cacheEntries()),
                Long.toString(config.cacheTtl().toMillis())
            );
        } catch (Exception failure) {
            log.warn("answer cache write skipped, exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    /** 命中输出前重新校验范围和每条固定来源版本，失效条目只当作未命中。 */
    public boolean valid(RetrievalScope scope, Entry entry) {
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            return false;
        }
        Set<Long> kbIds = new HashSet<>();
        for (RetrievalScope.KnowledgeBaseDepartment pair : scope.knowledgeBases()) {
            permissions.resolveFor(scope.userId(), List.of(pair.knowledgeBaseId()));
            kbIds.add(pair.knowledgeBaseId());
        }
        for (Citation citation : entry.citations()) {
            List<Long> found = mapper.citationKnowledgeBases(
                citation.documentId(),
                citation.documentVersion(),
                citation.chunkId()
            );
            if (
                found.isEmpty() ||
                !kbIds.contains(found.getFirst()) ||
                !entry.evidence().containsKey(citation.citationId())
            ) {
                return false;
            }
        }
        return !entry.citations().isEmpty() && VerificationProtocol.SUPPORTED.equals(entry.verification().status());
    }

    /** Redis 不可用或条目损坏时只走正常检索，不能使用进程内旧答案兜底。 */
    private List<Entry> read(String key) {
        try {
            List<String> values = redis.opsForList().range(key, 0, config.cacheEntries() - 1);
            List<Entry> entries = new ArrayList<>();
            if (values != null) {
                for (String value : values) {
                    Entry entry = json.readValue(value, Entry.class);
                    if (entry.expiresAt() > System.currentTimeMillis()) {
                        entries.add(entry);
                    }
                }
            }
            return entries;
        } catch (Exception failure) {
            log.warn("answer cache read skipped, exceptionType={}", failure.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 数值和否定条件必须一致，避免相似向量把不同年限或禁止条件合成同一个问题。 */
    private List<String> protectedTerms(String question) {
        return PROTECTED_TERMS.matcher(question).results().map(java.util.regex.MatchResult::group).toList();
    }

    /** 仅比较相同模型空间中的有效非零向量。 */
    public static double cosine(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.size() != right.size() || left.isEmpty()) {
            return -1;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double a = left.get(index);
            double b = right.get(index);
            if (!Double.isFinite(a) || !Double.isFinite(b)) {
                return -1;
            }
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        return leftNorm == 0 || rightNorm == 0 ? -1 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
