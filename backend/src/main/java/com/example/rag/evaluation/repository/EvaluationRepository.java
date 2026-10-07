package com.example.rag.evaluation.repository;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.evaluation.domain.EvaluationProtocol;
import com.example.rag.evaluation.dto.EvaluationModels.*;
import com.example.rag.evaluation.mapper.EvaluationMapper;
import com.example.rag.governance.mapper.GovernanceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 仅服务管理员评估模块，所有模型 IO 均由事务之外的 Worker 执行。 */
@Repository
public class EvaluationRepository {

    private final EvaluationMapper mapper;
    private final GovernanceMapper governance;
    private final ObjectMapper json;

    /** 注入持久化和快照序列化依赖。 */
    public EvaluationRepository(EvaluationMapper mapper, GovernanceMapper governance, ObjectMapper json) {
        this.mapper = mapper;
        this.governance = governance;
        this.json = json;
    }

    /** 在短事务内原子保存不可变数据集及按原顺序排列的案例。 */
    @Transactional
    public long create(DatasetRequest request, long creator) {
        long id = mapper.insertDataset(request, creator);
        int index = 0;
        for (CaseInput input : request.cases()) {
            mapper.insertCase(id, index++, encode(input));
        }
        audit(creator, EvaluationProtocol.DATASET_CREATE, id);
        return id;
    }

    /** 返回最近数据集，采用稳定 ID 倒序并限制读取数量。 */
    public List<Map<String, Object>> datasets(int limit) {
        return mapper.datasets(limit);
    }

    /** 查询唯一数据集，不允许访问已删除的数据集。 */
    public Map<String, Object> dataset(long id) {
        List<Map<String, Object>> rows = mapper.dataset(id);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.EVAL_404_NOT_FOUND);
        }
        return rows.getFirst();
    }

    /** 按创建顺序读取案例快照，调用方不得更新历史案例。 */
    public List<Map<String, Object>> cases(long datasetId) {
        return decodeRows(mapper.cases(datasetId), "snapshot");
    }

    /** 锁定数据集并控制全局排队上限，记录新实验和审计。 */
    @Transactional
    public long enqueue(long datasetId, RunRequest request, Object snapshot, long creator, int maxPending) {
        mapper.lockDatasets();
        int pending = mapper.pendingRuns();
        if (pending >= maxPending) {
            throw new BusinessException(ErrorCode.EVAL_409_QUEUE_FULL);
        }
        long id = mapper.enqueue(datasetId, request.name(), request.mode().name(), encode(snapshot));
        audit(creator, EvaluationProtocol.RUN_CREATE, id);
        return id;
    }

    /** 领取一个持久任务，互斥更新保证多实例不会同时执行同一实验。 */
    @Transactional
    public Map<String, Object> claim(String owner, long leaseMs) {
        mapper.expireRunning();
        List<Map<String, Object>> rows = mapper.claim(owner, leaseMs);
        return rows.isEmpty() ? null : decodeRows(rows, "snapshot").getFirst();
    }

    /** 每题开始前续租，失去租约的旧执行者不能继续产生费用。 */
    public boolean renew(long runId, String owner, long leaseMs) {
        return mapper.renew(runId, owner, leaseMs) == 1;
    }

    /** 只在有效租约下写入题目快照，迟到或重复写入不能覆盖历史。 */
    public void result(long runId, long caseId, String owner, Object result) {
        int saved = mapper.insertResult(runId, caseId, owner, encode(result));
        if (saved != 1) {
            throw new IllegalStateException("Evaluation lease lost");
        }
    }

    /** 保存实验终态及汇总；失效执行者不能改变被恢复器标记的结果。 */
    public void finish(long id, String owner, Object summary, String error) {
        String status = error == null ? EvaluationProtocol.COMPLETED : EvaluationProtocol.FAILED;
        mapper.finish(id, owner, status, encode(summary), error);
    }

    /** 返回最近实验摘要，避免一次加载所有回答正文。 */
    public List<Map<String, Object>> runs(long datasetId, int limit) {
        return decodeRows(mapper.runs(datasetId, limit), "summary");
    }

    /** 读取完整实验与每题快照，供配置核对和排序分析。 */
    public Map<String, Object> run(long id) {
        List<Map<String, Object>> rows = mapper.run(id);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.EVAL_404_NOT_FOUND);
        }
        Map<String, Object> run = decodeRows(decodeRows(rows, "summary"), "config_snapshot").getFirst();
        run.put("results", decodeRows(mapper.results(id), "snapshot"));
        return run;
    }

    /** 记录操作身份和追踪信息，审计不包含原始请求内容或认证凭证。 */
    public void audit(long actor, String action, Long id) {
        governance.audit(actor, action, id, MDC.get(TraceContextKeys.REQUEST_ID), MDC.get(TraceContextKeys.TRACE_ID));
    }

    /** JSONB 显式转换为结构化响应，保持既有快照响应结构。 */
    private List<Map<String, Object>> decodeRows(List<Map<String, Object>> rows, String field) {
        for (Map<String, Object> row : rows) {
            Object stored = row.get(field);
            if (stored != null) {
                try {
                    row.put(field, json.readTree(stored.toString()));
                } catch (JsonProcessingException failure) {
                    throw new IllegalStateException("Invalid evaluation snapshot", failure);
                }
            }
        }
        return rows;
    }

    /** 快照序列化失败应中断当前写入，不能保存半份实验配置。 */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot serialize evaluation snapshot", failure);
        }
    }
}
