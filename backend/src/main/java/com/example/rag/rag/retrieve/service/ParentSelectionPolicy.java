package com.example.rag.rag.retrieve.service;

import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.example.rag.rag.retrieve.domain.RetrievalCandidate;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ParentSelectionPolicy {

    private final RetrievalProperties config;

    /** 配置候选分布阈值与每文档证据上限。 */
    public ParentSelectionPolicy(RetrievalProperties config) {
        this.config = config;
    }

    /** 按最终上限、分数分布和文档多样性选 Parent，重复命中同一 Parent 只保留最高排名。 */
    public List<RetrievalCandidate> select(List<RetrievalCandidate> ranked, int limit) {
        if (ranked.isEmpty() || score(ranked.getFirst()) <= 0) {
            return List.of();
        }
        double scoreFloor = score(ranked.getFirst()) * config.relativeScoreFloor();
        Set<Long> parents = new HashSet<>();
        List<RetrievalCandidate> unique = ranked
            .stream()
            .filter(candidate -> score(candidate) >= scoreFloor && parents.add(candidate.hit().parentChunkId()))
            .toList();
        Set<Long> selectedParents = new HashSet<>();
        Map<Long, Integer> documentCounts = new HashMap<>();
        List<RetrievalCandidate> selected = new ArrayList<>();
        // 第一轮优先跨文档取证；第二轮补入同文档证据，始终遵守最终上限。
        append(unique, selected, selectedParents, documentCounts, 1, limit);
        append(unique, selected, selectedParents, documentCounts, config.maxParentsPerDocument(), limit);
        return List.copyOf(selected);
    }

    /** 按当前文档配额补充证据，同时避免重复 Parent。 */
    private void append(
        List<RetrievalCandidate> candidates,
        List<RetrievalCandidate> selected,
        Set<Long> parents,
        Map<Long, Integer> documentCounts,
        int quota,
        int limit
    ) {
        for (RetrievalCandidate candidate : candidates) {
            if (selected.size() >= limit) {
                return;
            }
            long documentId = candidate.hit().documentId();
            int count = documentCounts.getOrDefault(documentId, 0);
            if (count < quota && parents.add(candidate.hit().parentChunkId())) {
                selected.add(candidate);
                documentCounts.put(documentId, count + 1);
            }
        }
    }

    /** 优先使用精排分数，没有精排时按 RRF 分数计算动态阈值。 */
    private double score(RetrievalCandidate candidate) {
        return candidate.rerankScore() == null ? candidate.rrfScore() : candidate.rerankScore();
    }
}
