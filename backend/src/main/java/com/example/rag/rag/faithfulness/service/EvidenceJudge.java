package com.example.rag.rag.faithfulness.service;

import com.example.rag.conversation.domain.CitationProtocol;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.rag.adaptive.service.StructuredModelService;
import com.example.rag.rag.chat.service.ContextBuilder.Evidence;
import com.example.rag.rag.faithfulness.config.FaithfulnessProperties;
import com.example.rag.rag.faithfulness.domain.VerificationProtocol;
import com.example.rag.rag.model.domain.ModelStage;
import com.example.rag.rag.prompt.service.PromptCatalog;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 仅核验实际入模证据，模型超时或非法输出均不能视为已核实。 */
@Service
public class EvidenceJudge {

    public record Verification(
        String status,
        String confidenceLevel,
        double coverage,
        List<String> unsupportedClaims,
        boolean conflictingEvidence,
        String reasonCode
    ) {}

    private final StructuredModelService models;
    private final FaithfulnessProperties config;
    private final ChunkTokenizer tokenizer;
    private final ObjectMapper json;
    private final PromptCatalog prompts;

    /** 配置独立核验时限、输入预算与结构化模型调用。 */
    public EvidenceJudge(
        StructuredModelService models,
        FaithfulnessProperties config,
        ChunkTokenizer tokenizer,
        ObjectMapper json,
        PromptCatalog prompts
    ) {
        this.models = models;
        this.config = config;
        this.tokenizer = tokenizer;
        this.json = json;
        this.prompts = prompts;
    }

    /** 校验回答引用和证据覆盖，UNKNOWN 用于核验故障，UNSUPPORTED 用于确定负例。 */
    public Verification verify(String question, String answer, Map<String, Evidence> evidence) {
        if (evidence.isEmpty()) {
            return new Verification(
                VerificationProtocol.UNSUPPORTED,
                VerificationProtocol.LOW,
                0,
                List.of("没有可用的入模证据"),
                false,
                VerificationProtocol.NO_EVIDENCE
            );
        }
        if (answer.contains(CitationProtocol.INVALID_MARKER) || invalidCitations(answer, evidence.keySet())) {
            return new Verification(
                VerificationProtocol.UNSUPPORTED,
                VerificationProtocol.LOW,
                0,
                List.of("回答包含无效引用"),
                false,
                VerificationProtocol.INVALID_CITATION
            );
        }
        if (!config.enabled()) {
            return unknown(VerificationProtocol.DISABLED);
        }
        try {
            Map<String, String> sources = new LinkedHashMap<>();
            evidence.forEach((id, snapshot) -> sources.put(id, snapshot.content()));
            String input = json.writeValueAsString(
                Map.of(
                    VerificationProtocol.QUESTION,
                    question,
                    VerificationProtocol.ANSWER,
                    answer,
                    VerificationProtocol.EVIDENCE,
                    sources
                )
            );
            if (tokenizer.count(input) > config.inputTokens()) {
                return unknown(VerificationProtocol.INPUT_BUDGET);
            }
            JsonNode judged = models.call(
                ModelStage.FAITHFULNESS.name(),
                prompts.faithfulness(),
                input,
                config.outputTokens(),
                config.timeout()
            );
            return parse(judged, answer, sources.keySet());
        } catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Judge interrupted", failure);
            }
            return unknown(VerificationProtocol.JUDGE_FAILED);
        }
    }

    /** 对状态、覆盖率、断言及矛盾字段执行联合校验，再映射置信度。 */
    private Verification parse(JsonNode node, String answer, Set<String> citations) {
        String status = node.path(VerificationProtocol.STATUS).asText();
        Set<String> statuses = Set.of(
            VerificationProtocol.SUPPORTED,
            VerificationProtocol.PARTIALLY_SUPPORTED,
            VerificationProtocol.UNSUPPORTED,
            VerificationProtocol.UNKNOWN
        );
        JsonNode coverageNode = node.path(VerificationProtocol.COVERAGE);
        JsonNode claimsNode = node.path(VerificationProtocol.UNSUPPORTED_CLAIMS);
        JsonNode conflictNode = node.path(VerificationProtocol.CONFLICTING_EVIDENCE);
        if (
            !statuses.contains(status) ||
            !coverageNode.isNumber() ||
            !claimsNode.isArray() ||
            claimsNode.size() > config.maxUnsupportedClaims() ||
            !conflictNode.isBoolean()
        ) {
            return unknown(VerificationProtocol.INVALID_RESPONSE);
        }
        double coverage = coverageNode.doubleValue();
        if (!Double.isFinite(coverage) || coverage < 0 || coverage > 1) {
            return unknown(VerificationProtocol.INVALID_COVERAGE);
        }
        List<String> claims = new ArrayList<>();
        for (JsonNode claim : claimsNode) {
            if (
                !claim.isTextual() || claim.asText().isBlank() || claim.asText().length() > config.maxClaimCharacters()
            ) {
                return unknown(VerificationProtocol.INVALID_CLAIM);
            }
            claims.add(claim.asText());
        }
        boolean conflict = conflictNode.booleanValue();
        if (VerificationProtocol.SUPPORTED.equals(status) && (coverage != 1 || !claims.isEmpty() || conflict)) {
            return unknown(VerificationProtocol.INCONSISTENT_JUDGE);
        }
        if (VerificationProtocol.UNKNOWN.equals(status)) {
            return unknown(VerificationProtocol.JUDGE_UNKNOWN);
        }
        boolean hasCitation = citations.stream().anyMatch(id -> answer.contains("[" + id + "]"));
        if (VerificationProtocol.SUPPORTED.equals(status) && !hasCitation) {
            return unknown(VerificationProtocol.MISSING_CITATION);
        }
        String confidence = VerificationProtocol.LOW;
        if (VerificationProtocol.SUPPORTED.equals(status) && hasCitation) {
            confidence = VerificationProtocol.HIGH;
        } else if (
            VerificationProtocol.PARTIALLY_SUPPORTED.equals(status) &&
            hasCitation &&
            coverage >= config.mediumCoverage() &&
            !conflict
        ) {
            confidence = VerificationProtocol.MEDIUM;
        }
        return new Verification(
            status,
            confidence,
            coverage,
            List.copyOf(claims),
            conflict,
            VerificationProtocol.JUDGED
        );
    }

    /** 核验故障只返回未知状态，不生成事实性断言。 */
    public Verification unknown(String reason) {
        return new Verification(VerificationProtocol.UNKNOWN, VerificationProtocol.LOW, 0, List.of(), false, reason);
    }

    /** 独立复查 Judge 输入中的编号白名单，防止绕过流式过滤的调用伪造来源。 */
    private boolean invalidCitations(String answer, Set<String> allowed) {
        java.util.regex.Matcher markers = CitationProtocol.MARKER_PATTERN.matcher(answer);
        while (markers.find()) {
            if (!allowed.contains(CitationProtocol.ID_PREFIX + markers.group(1))) {
                return true;
            }
        }
        return false;
    }
}
