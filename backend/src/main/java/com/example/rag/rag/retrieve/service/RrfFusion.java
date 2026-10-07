package com.example.rag.rag.retrieve.service;

import com.example.rag.rag.retrieve.domain.RetrievalCandidate;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RrfFusion {

    /** 两路按 Chunk ID 去重；每一路重复记录只取首次排名，同分按 ID 保持稳定。 */
    public List<RetrievalCandidate> fuse(
        List<VectorSearchRepository.Hit> vectors,
        List<KeywordRetriever.KeywordHit> keywords,
        int k,
        int limit
    ) {
        Map<Long, RetrievalCandidate> candidates = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (int index = 0; index < vectors.size(); index++) {
            VectorSearchRepository.Hit hit = vectors.get(index);
            if (seen.add(hit.chunkId())) {
                int rank = index + 1;
                candidates.put(
                    hit.chunkId(),
                    new RetrievalCandidate(hit, rank, 1 - hit.distance(), null, null, 1.0 / (k + rank), null)
                );
            }
        }
        seen.clear();
        for (int index = 0; index < keywords.size(); index++) {
            KeywordRetriever.KeywordHit keyword = keywords.get(index);
            if (!seen.add(keyword.chunkId())) {
                continue;
            }
            int rank = index + 1;
            RetrievalCandidate previous = candidates.get(keyword.chunkId());
            candidates.put(
                keyword.chunkId(),
                new RetrievalCandidate(
                    previous == null ? keyword.hit() : previous.hit(),
                    previous == null ? null : previous.vectorRank(),
                    previous == null ? null : previous.vectorScore(),
                    rank,
                    keyword.keywordScore(),
                    1.0 / (k + rank) + (previous == null ? 0 : previous.rrfScore()),
                    null
                )
            );
        }
        return candidates
            .values()
            .stream()
            .sorted(
                Comparator.comparingDouble(RetrievalCandidate::rrfScore)
                    .reversed()
                    .thenComparingLong(candidate -> candidate.hit().chunkId())
            )
            .limit(limit)
            .toList();
    }
}
