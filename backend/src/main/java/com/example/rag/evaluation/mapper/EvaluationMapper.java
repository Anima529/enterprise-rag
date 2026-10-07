package com.example.rag.evaluation.mapper;

import com.example.rag.evaluation.dto.EvaluationModels.DatasetRequest;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 评测持久化契约，排队锁、租约和来源查询统一由 XML 管理。 */
@Mapper
public interface EvaluationMapper {
    /** 建立固定执行身份的数据集并返回 ID，调用方负责短事务。 */
    long insertDataset(@Param("request") DatasetRequest request, @Param("creator") long creator);

    /** 按原始顺序保存不可变案例 JSONB。 */
    int insertCase(@Param("datasetId") long datasetId, @Param("index") int index, @Param("snapshot") String snapshot);

    /** 按稳定 ID 倒序读取最近数据集及案例数。 */
    List<Map<String, Object>> datasets(@Param("limit") int limit);

    /** 读取唯一未删除数据集，未找到时返回空列表。 */
    List<Map<String, Object>> dataset(@Param("id") long id);

    /** 按案例顺序读取文本形式的快照供 Repository 解码。 */
    List<Map<String, Object>> cases(@Param("datasetId") long datasetId);

    /** 顺序锁定数据集行以串行控制全局评测队列上限。 */
    List<Long> lockDatasets();

    /** 统计待执行和执行中任务，不计已结束任务。 */
    int pendingRuns();

    /** 保存冻结配置的异步实验并返回 ID。 */
    long enqueue(
        @Param("datasetId") long datasetId,
        @Param("name") String name,
        @Param("mode") String mode,
        @Param("snapshot") String snapshot
    );

    /** 将过期运行租约标记为失败，保留已有每题结果。 */
    int expireRunning();

    /** SKIP LOCKED 领取一个任务，并返回本次取得的执行租约快照。 */
    List<Map<String, Object>> claim(@Param("owner") String owner, @Param("leaseMs") long leaseMs);

    /** 仅允许有效 owner 续租，更新数量用于判断执行权是否失效。 */
    int renew(@Param("runId") long runId, @Param("owner") String owner, @Param("leaseMs") long leaseMs);

    /** 有效租约下幂等保存题目结果，旧 owner 不能覆盖历史。 */
    int insertResult(
        @Param("runId") long runId,
        @Param("caseId") long caseId,
        @Param("owner") String owner,
        @Param("snapshot") String snapshot
    );

    /** 有效 owner 保存终态和汇总并释放租约。 */
    int finish(
        @Param("id") long id,
        @Param("owner") String owner,
        @Param("status") String status,
        @Param("summary") String summary,
        @Param("error") String error
    );

    /** 查询同一题集的最近实验摘要。 */
    List<Map<String, Object>> runs(@Param("datasetId") long datasetId, @Param("limit") int limit);

    /** 读取实验的完整配置与汇总，未找到时返回空列表。 */
    List<Map<String, Object>> run(@Param("id") long id);

    /** 按案例 ID 读取该实验已保存的结果。 */
    List<Map<String, Object>> results(@Param("runId") long runId);

    /** 查询标注 Child 所在知识库，仅接受当前有效版本。 */
    List<Long> childKnowledgeBases(@Param("chunkId") long chunkId);

    /** 检查标注 Child 仍属于当前已完成文档。 */
    int currentChildCount(@Param("chunkId") long chunkId);

    /** 冻结知识库当前版本及 Chunk 的模型和正文摘要。 */
    List<Map<String, Object>> documents(@Param("knowledgeBaseId") long knowledgeBaseId);
}
