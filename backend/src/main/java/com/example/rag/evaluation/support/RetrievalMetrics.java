package com.example.rag.evaluation.support;

import com.example.rag.evaluation.dto.EvaluationModels.Metrics;
import java.util.*;

/** 相关 Child 去重；无相关 Child 的无答案/权限案例不进入检索指标分母。 */
public final class RetrievalMetrics {

    /** 指标算法只通过静态入口调用。 */
    private RetrievalMetrics() {
    }

    /** Hit 为至少一个命中，Recall 按全部不同相关 Child，RR 使用第一个相关 Child 的排名倒数。 */
    public static Metrics calculate(List<Long> expected, List<Long> ranked, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("Positive metric K required");
        }
        Set<Long> relevant = new HashSet<>(expected);
        if (relevant.isEmpty()) {
            return new Metrics(null, null, null, 0, k);
        }
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ranked));
        int count = 0;
        double reciprocal = 0;
        for (int index = 0; index < Math.min(k, unique.size()); index++) {
            if (relevant.contains(unique.get(index))) {
                count++;
                if (reciprocal == 0) {
                    reciprocal = 1.0 / (index + 1);
                }
            }
        }
        return new Metrics(count > 0 ? 1.0 : 0.0, (double) count / relevant.size(), reciprocal, relevant.size(), k);
    }
}
