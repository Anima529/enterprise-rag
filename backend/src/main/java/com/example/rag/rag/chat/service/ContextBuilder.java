package com.example.rag.rag.chat.service;

import com.example.rag.conversation.domain.CitationProtocol;
import com.example.rag.conversation.domain.ConversationProtocol;
import com.example.rag.conversation.dto.ConversationModels.Citation;
import com.example.rag.conversation.service.CitationPreviewBuilder;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.document.support.parser.SourceMetadata;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.chat.support.EvidenceMetadata;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.rag.prompt.service.PromptCatalog;
import com.example.rag.rag.retrieve.domain.RetrievalCandidate;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository.Hit;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ContextBuilder {

    public record Evidence(String content, String metadataJson) {}

    public record Context(GenerationRequest request, List<Citation> citations, Map<String, Evidence> evidence) {}

    private final ChatProperties config;
    private final ChunkTokenizer tokenizer;
    private final ObjectMapper json;
    private final CitationPreviewBuilder previews;
    private final String prompt;
    private final EvidenceMetadata metadata;

    /** 配置证据预算和来源预览，并加载回答 Prompt 模板。 */
    public ContextBuilder(
        ChatProperties config,
        ChunkTokenizer tokenizer,
        ObjectMapper json,
        CitationPreviewBuilder previews,
        PromptCatalog prompts
    ) {
        this.config = config;
        this.tokenizer = tokenizer;
        this.json = json;
        this.previews = previews;
        metadata = new EvidenceMetadata(json);
        prompt = prompts.answer();
    }

    /** 筛除弱证据并按父块去重，在 Token 预算内构建带版本来源的上下文。 */
    public Context build(String question, List<Hit> hits) {
        return buildEvidence(
            question,
            hits
                .stream()
                .filter(hit -> 1 - hit.distance() >= config.minSimilarity())
                .toList()
        );
    }

    /** Hybrid 已分别执行分数门槛，关键词证据不能再用向量距离过滤。 */
    public Context buildCandidates(String question, List<RetrievalCandidate> candidates) {
        return buildEvidence(question, candidates.stream().map(RetrievalCandidate::hit).toList());
    }

    /** 对实际证据去重并裁剪上下文，同时保留入模正文用于历史原文定位。 */
    private Context buildEvidence(String question, List<Hit> hits) {
        List<Citation> citations = new ArrayList<>();
        Map<String, Evidence> snapshots = new LinkedHashMap<>();
        Set<Long> parentChunkIds = new HashSet<>();
        Hit previous = null;
        StringBuilder evidence = new StringBuilder();
        String messagePrefix = question + "\n\n证据：";
        int remainingTokens = config.contextTokens() - tokenizer.count(prompt) - tokenizer.count(messagePrefix);
        for (Hit hit : arrangeAdjacent(hits)) {
            if (!parentChunkIds.add(hit.parentChunkId())) {
                continue;
            }
            String citationId = CitationProtocol.ID_PREFIX + (citations.size() + 1);
            String header = adjacent(previous, hit)
                ? "\n\n[" + citationId + "]（同文档接续片段）\n"
                : "\n[" + citationId + "] 文档：" + hit.documentName() + "，版本：" + hit.documentVersion() + "\n";
            int contentTokenBudget = remainingTokens - tokenizer.count(header);
            if (contentTokenBudget < config.minContextTokens()) {
                break;
            }
            String content = hit.parentContent();
            if (tokenizer.count(content) > contentTokenBudget) {
                content = content.substring(0, tokenizer.prefixEnd(content, 0, contentTokenBudget));
            }
            if (content.isBlank()) {
                continue;
            }
            int totalTokens = tokenizer.count(prompt) + tokenizer.count(messagePrefix + evidence + header + content);
            while (totalTokens > config.contextTokens() && !content.isEmpty()) {
                int reducedTokens = tokenizer.count(content) - (totalTokens - config.contextTokens()) - 1;
                if (reducedTokens <= 0) {
                    content = "";
                    break;
                }
                content = content.substring(0, tokenizer.prefixEnd(content, 0, reducedTokens));
                totalTokens = tokenizer.count(prompt) + tokenizer.count(messagePrefix + evidence + header + content);
            }
            if (content.isBlank()) {
                continue;
            }
            remainingTokens = config.contextTokens() - totalTokens;
            evidence.append(header).append(content);
            previous = hit;
            Integer lineStart = null;
            Integer lineEnd = null;
            ObjectNode snapshotMetadata = metadata.clip(hit.metadataJson(), content);
            JsonNode sources = snapshotMetadata.path(SourceMetadata.SOURCES);
            for (JsonNode source : sources) {
                JsonNode metadata = source.path(SourceMetadata.METADATA);
                if (metadata.path(SourceMetadata.LINE_START).isInt()) {
                    lineStart =
                        lineStart == null
                            ? metadata.path(SourceMetadata.LINE_START).intValue()
                            : Math.min(lineStart, metadata.path(SourceMetadata.LINE_START).intValue());
                }
                if (metadata.path(SourceMetadata.LINE_END).isInt()) {
                    lineEnd =
                        lineEnd == null
                            ? metadata.path(SourceMetadata.LINE_END).intValue()
                            : Math.max(lineEnd, metadata.path(SourceMetadata.LINE_END).intValue());
                }
            }
            String snippet = content.substring(0, Math.min(content.length(), config.snippetCharacters()));
            snapshots.put(citationId, new Evidence(content, snapshotMetadata.toString()));
            citations.add(
                new Citation(
                    citationId,
                    hit.documentId(),
                    hit.documentVersion(),
                    hit.documentName(),
                    hit.pageNumber(),
                    hit.chapter(),
                    hit.section(),
                    lineStart,
                    lineEnd,
                    hit.parentChunkId(),
                    snippet,
                    previews.tables(sources, snippet.length()),
                    previews.preview(sources, snippet)
                )
            );
        }
        GenerationRequest request = new GenerationRequest(
            prompt,
            List.of(new Message(ConversationProtocol.USER, messagePrefix + evidence)),
            config.maxOutputTokens()
        );
        return new Context(request, List.copyOf(citations), Map.copyOf(snapshots));
    }

    /** 同一文档版本中相邻 Parent 合并排列，保留各自引用编号与来源快照。 */
    private List<Hit> arrangeAdjacent(List<Hit> hits) {
        List<Hit> arranged = new ArrayList<>();
        Set<Long> included = new HashSet<>();
        for (Hit anchor : hits) {
            if (!included.add(anchor.parentChunkId())) {
                continue;
            }
            List<Hit> group = new ArrayList<>();
            group.add(anchor);
            boolean extended;
            do {
                extended = false;
                for (Hit candidate : hits) {
                    if (
                        !included.contains(candidate.parentChunkId()) &&
                        group.stream().anyMatch(member -> adjacent(member, candidate) || adjacent(candidate, member))
                    ) {
                        group.add(candidate);
                        included.add(candidate.parentChunkId());
                        extended = true;
                    }
                }
            } while (extended);
            group.sort(Comparator.comparingInt(this::parentIndex));
            arranged.addAll(group);
        }
        return List.copyOf(arranged);
    }

    /** 只有同文档、同版本且真实 Parent 序号连续时才能合并上下文，章节边界仍保留各自引用。 */
    private boolean adjacent(Hit previous, Hit next) {
        return (
            previous != null &&
            previous.documentId() == next.documentId() &&
            previous.documentVersion() == next.documentVersion() &&
            parentIndex(previous) >= 0 &&
            parentIndex(next) == parentIndex(previous) + 1
        );
    }

    /** 从候选 SQL 提供的序号判断物理相邻，旧元数据缺失时不猜测。 */
    private int parentIndex(Hit hit) {
        try {
            return json.readTree(hit.metadataJson()).path(SourceMetadata.PARENT_CHUNK_INDEX).asInt(-1);
        } catch (IOException failure) {
            throw new IllegalStateException("Invalid stored chunk metadata", failure);
        }
    }
}
