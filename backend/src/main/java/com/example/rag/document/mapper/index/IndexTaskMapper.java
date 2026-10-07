package com.example.rag.document.mapper.index;

import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.dto.index.IndexStepResponse;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IndexTaskMapper {
    /** 锁定一个到期待执行的有效来源任务，跳过其他领取者的锁。 */
    Long nextPending();

    /** 锁定任务行以串行处理租约和状态更新。 */
    IndexTaskLease lock(long id);

    /** 读取任务及当前租约信息。 */
    IndexTaskLease find(long id);

    /** 将待执行任务置为运行中并登记新的租约与尝试次数。 */
    void claim(@Param("id") long id, @Param("owner") String owner, @Param("timeoutMs") long timeoutMs);

    /** 同步文档版本的阶段状态与错误信息。 */
    void versionStage(
        @Param("documentId") long documentId,
        @Param("version") int version,
        @Param("stage") IndexStage stage
    );

    /** 同步文档的阶段状态与错误信息。 */
    void documentStage(
        @Param("documentId") long documentId,
        @Param("version") int version,
        @Param("stage") IndexStage stage
    );

    /** 原子切换已完成版本及原文件元数据，保留旧版本直到新版本成功。 */
    int activate(@Param("id") long id, @Param("version") int version);

    /** 安排已失效旧版本的延迟索引清理。 */
    void scheduleOld(@Param("id") long id, @Param("version") int version, @Param("graceMs") long graceMs);

    /** 记录本次尝试的阶段开始时间。 */
    void startStep(@Param("task") IndexTaskLease task, @Param("stage") IndexStage stage);

    /** 保存阶段终态、错误信息和结束时间。 */
    void finishStep(
        @Param("task") IndexTaskLease task,
        @Param("status") String status,
        @Param("code") String code,
        @Param("message") String message
    );

    /** 推进任务阶段并续租有效租约。 */
    void stage(@Param("id") long id, @Param("stage") IndexStage stage);

    /** 将有效租约对应的任务标记为完成。 */
    void complete(long id);

    /** 保存失败原因和重试计划并释放当前租约。 */
    void fail(
        @Param("id") long id,
        @Param("status") String status,
        @Param("code") String code,
        @Param("message") String message,
        @Param("retry") boolean retry,
        @Param("delayMs") long delayMs
    );

    /** 锁定需要恢复的过期任务或来源失效任务。 */
    List<Long> recoverable(int limit);

    /** 将可重试的失败任务重新置为待执行。 */
    void manualRetry(long id);

    /** 分页查询任务的各次阶段记录。 */
    List<IndexStepResponse> steps(@Param("id") long id, @Param("limit") int limit, @Param("offset") long offset);

    /** 统计任务阶段记录数。 */
    long countSteps(long id);
}
