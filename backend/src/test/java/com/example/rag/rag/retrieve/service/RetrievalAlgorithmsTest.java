package com.example.rag.rag.retrieve.service;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.example.rag.rag.retrieve.domain.RetrievalCandidate;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository;
import com.example.rag.rag.retrieve.support.KeywordAnalyzer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalAlgorithmsTest {

    /** 与 Phase 0 一致处理中文术语、全角缩写、条款号与重复词项。 */
    @Test
    void shouldMatchPhase0KeywordContract() {
        assertThat(KeywordAnalyzer.terms("第 3.2 条 带薪年假 ＳＳＯ ISO-27001 年假")).containsExactly(
            "clause3x2",
            "带薪",
            "薪年",
            "年假",
            "sso",
            "iso-27001"
        );
        assertThat(KeywordAnalyzer.terms("𠀀中 文 预算2026 600")).containsExactly("𠀀中", "文", "预算", "2026", "600");
        assertThat(KeywordAnalyzer.query("😀[]! ' | &")).isEmpty();
        assertThat(KeywordAnalyzer.query("第3.2条 SSO")).isEqualTo("'clause3x2' | 'sso'");
    }

    /** RRF 每一路只贡献一次排名，跨路重复相加且同分按 ID 稳定。 */
    @Test
    void shouldFuseDuplicateChunksAndKeepBothOriginalRanksAndScores() {
        List<RetrievalCandidate> fused = new RrfFusion().fuse(
            List.of(hit(2, 20, 1), hit(1, 10, 1), hit(2, 20, 1)),
            List.of(keyword(1, 10, 1, .3), keyword(2, 20, 1, .2)),
            60,
            20
        );
        assertThat(fused)
            .extracting(candidate -> candidate.hit().chunkId())
            .containsExactly(1L, 2L);
        assertThat(fused.getFirst().rrfScore()).isEqualTo(1.0 / 61 + 1.0 / 62);
        assertThat(fused.getFirst().vectorRank()).isEqualTo(2);
        assertThat(fused.getFirst().keywordRank()).isEqualTo(1);
        assertThat(fused.getFirst().keywordScore()).isEqualTo(.3);
    }

    /** 一路为空、两路为空和融合上限均保持可预测结果。 */
    @Test
    void shouldHandleEmptyRoutesAndBoundFusion() {
        RrfFusion fusion = new RrfFusion();
        assertThat(fusion.fuse(List.of(), List.of(), 60, 20)).isEmpty();
        assertThat(fusion.fuse(List.of(hit(2, 20, 1), hit(1, 10, 1)), List.of(), 60, 1)).hasSize(1);
        assertThat(
            fusion
                .fuse(List.of(), List.of(keyword(1, 10, 1, .1)), 60, 20)
                .getFirst()
                .vectorScore()
        ).isNull();
    }

    /** 动态证据按分数差距收缩，优先文档多样性并去除相同 Parent。 */
    @Test
    void shouldDeduplicateParentsPreferDiverseDocumentsAndShrinkByScoreDistribution() {
        ParentSelectionPolicy policy = new ParentSelectionPolicy(config());
        List<RetrievalCandidate> ranked = List.of(
            candidate(1, 10, 1, .9),
            candidate(2, 10, 1, .8),
            candidate(3, 11, 1, .7),
            candidate(4, 20, 2, .6),
            candidate(5, 30, 3, .1)
        );
        assertThat(policy.select(ranked, 3))
            .extracting(candidate -> candidate.hit().parentChunkId())
            .containsExactly(10L, 20L, 11L);
        assertThat(policy.select(ranked, 1)).hasSize(1);
        assertThat(policy.select(List.of(candidate(1, 10, 1, 0)), 5)).isEmpty();
    }

    /** 构造真实路线的配置预算，供排序及并行故障回归共用。 */
    static RetrievalProperties config() {
        return new RetrievalProperties(
            RetrievalProperties.Complexity.STANDARD,
            4,
            8,
            60,
            Duration.ofMillis(300),
            Duration.ofMillis(300),
            2,
            2,
            .5,
            2,
            250,
            new RetrievalProperties.Profile(8, 1, 8, 3),
            new RetrievalProperties.Profile(30, 30, 20, 5),
            new RetrievalProperties.Profile(20, 20, 40, 5)
        );
    }

    /** 构造包含父块、文档和版本的授权向量候选。 */
    static VectorSearchRepository.Hit hit(long id, long parent, long document) {
        return new VectorSearchRepository.Hit(
            id,
            parent,
            1,
            1,
            document,
            1,
            "年假五天",
            "{\"sources\":[]}",
            .1,
            "年假制度：带薪年假为五天。",
            "制度.txt",
            null,
            null,
            null
        );
    }

    /** 构造保留原始 FTS 分数的词项候选。 */
    static KeywordRetriever.KeywordHit keyword(long id, long parent, long document, double score) {
        VectorSearchRepository.Hit hit = hit(id, parent, document);
        return new KeywordRetriever.KeywordHit(
            id,
            parent,
            1,
            1,
            document,
            1,
            hit.content(),
            hit.metadataJson(),
            1,
            hit.parentContent(),
            hit.documentName(),
            null,
            null,
            null,
            score
        );
    }

    /** 构造带精排分数的候选，验证动态 Top-K 规则。 */
    static RetrievalCandidate candidate(long id, long parent, long document, double score) {
        return new RetrievalCandidate(hit(id, parent, document), 1, .9, null, null, .02, score);
    }
}
