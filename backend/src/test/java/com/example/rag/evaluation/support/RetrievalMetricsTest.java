package com.example.rag.evaluation.support;

import static org.assertj.core.api.Assertions.*;

import com.example.rag.evaluation.dto.EvaluationModels;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    /** 多相关 Child、重复候选和 Top-K 截断的手算值必须与程序相同。 */
    @Test
    void shouldMatchHandCalculatedMetricsWithMultipleRelevantChunks() {
        EvaluationModels.Metrics metrics = RetrievalMetrics.calculate(
            List.of(2L, 4L, 4L),
            List.of(9L, 2L, 2L, 8L, 4L),
            3
        );
        assertThat(metrics.hit()).isEqualTo(1);
        assertThat(metrics.recall()).isEqualTo(0.5);
        assertThat(metrics.reciprocalRank()).isEqualTo(0.5);
        assertThat(metrics.relevantCount()).isEqualTo(2);
    }

    /** 无相关项时不进入分母，确定未召回时三个检索指标均为零。 */
    @Test
    void shouldSeparateNoAnswerCasesFromRetrievalFailures() {
        assertThat(RetrievalMetrics.calculate(List.of(), List.of(1L), 5).hit()).isNull();
        EvaluationModels.Metrics failed = RetrievalMetrics.calculate(List.of(2L), List.of(), 5);
        assertThat(failed.hit()).isZero();
        assertThat(failed.recall()).isZero();
        assertThat(failed.reciprocalRank()).isZero();
    }

    /** MRR@K 只检查截断后的不同候选，第 K 位之后的相关项不能算作命中。 */
    @Test
    void shouldExcludeHitsOutsideMetricBudget() {
        EvaluationModels.Metrics metrics = RetrievalMetrics.calculate(List.of(4L), List.of(1L, 2L, 4L), 2);
        assertThat(metrics.hit()).isZero();
        assertThatThrownBy(() -> RetrievalMetrics.calculate(List.of(), List.of(), 0)).isInstanceOf(
            IllegalArgumentException.class
        );
    }
}
