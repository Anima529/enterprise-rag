package com.example.rag.governance.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticAnswerCacheTest {

    /** 语义相似计算必须拒绝不同维度、零向量和非有限值，避免坏数据命中。 */
    @Test
    void shouldCompareOnlyValidVectorsInTheSameSpace() {
        assertThat(SemanticAnswerCache.cosine(List.of(1f, 0f), List.of(1f, 0f))).isEqualTo(1);
        assertThat(SemanticAnswerCache.cosine(List.of(1f, 0f), List.of(0f, 1f))).isZero();
        assertThat(SemanticAnswerCache.cosine(List.of(0f), List.of(0f))).isEqualTo(-1);
        assertThat(SemanticAnswerCache.cosine(List.of(1f), List.of(1f, 0f))).isEqualTo(-1);
        assertThat(SemanticAnswerCache.cosine(List.of(Float.NaN), List.of(1f))).isEqualTo(-1);
    }
}
