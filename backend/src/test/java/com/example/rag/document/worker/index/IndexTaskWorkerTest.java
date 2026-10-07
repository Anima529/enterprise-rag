package com.example.rag.document.worker.index;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.rag.document.config.index.IndexTaskProperties;
import com.example.rag.document.domain.index.IndexStage;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.document.exception.index.LostTaskLease;
import com.example.rag.document.repository.index.IndexTaskStore;
import com.example.rag.document.service.index.IndexStageProcessor;
import com.example.rag.document.service.index.IndexTaskContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class IndexTaskWorkerTest {

    /** 构造 Worker 测试使用的固定任务租约。 */
    private IndexTaskLease lease() {
        return new IndexTaskLease(
            1,
            2,
            1,
            "RUNNING",
            IndexStage.PARSING,
            1,
            0,
            0,
            UUID.randomUUID().toString(),
            Instant.now().plusSeconds(60),
            true,
            true
        );
    }

    /** 验证阶段按序执行且只在索引写入成功后完成。 */
    @Test
    void shouldRunEveryStageInOrderAndCompleteOnlyAfterIndexWritesSucceed() {
        IndexTaskStore store = mock(IndexTaskStore.class);
        IndexTaskLease task = lease();
        List<IndexStage> seen = new ArrayList<>();
        List<IndexStageProcessor> processors = new ArrayList<>();
        for (IndexStage stage : List.of(
            IndexStage.PARSING,
            IndexStage.CHUNKING,
            IndexStage.EMBEDDING,
            IndexStage.INDEXING
        )) {
            processors.add(
                new IndexStageProcessor() {
                    /** 声明该测试处理器模拟的索引阶段。 */
                    public IndexStage stage() {
                        return stage;
                    }

                    /** 执行本用例定义的阶段处理或模拟阶段结果。 */
                    public void process(IndexTaskContext context) {
                        seen.add(stage);
                    }
                }
            );
        }
        new IndexTaskWorker(store, processors).run(task);
        assertThat(seen).containsExactly(
            IndexStage.PARSING,
            IndexStage.CHUNKING,
            IndexStage.EMBEDDING,
            IndexStage.INDEXING
        );
        InOrder order = inOrder(store);
        for (IndexStage stage : seen) {
            order.verify(store).checkActive(task);
            order.verify(store).advance(task, stage);
        }
        verify(store, never()).fail(any(), any());
    }

    /** 验证缺少处理器明确失败而非假装索引成功。 */
    @Test
    void shouldFailMissingProcessorWithoutPretendingDocumentIsIndexed() {
        IndexTaskStore store = mock(IndexTaskStore.class);
        IndexTaskLease task = lease();
        new IndexTaskWorker(store, List.of()).run(task);
        verify(store).fail(
            eq(task),
            argThat(
                failure ->
                    !failure.retryable() &&
                    failure.code().equals("DOC_503_STAGE_UNAVAILABLE") &&
                    failure.getMessage().contains("Phase 6")
            )
        );
        verify(store, never()).advance(any(), any());
    }

    /** 验证临时模型失败保留可重试属性且不继续索引。 */
    @Test
    void shouldPreserveTemporaryModelFailureAndStopBeforeIndexing() {
        IndexTaskStore store = mock(IndexTaskStore.class);
        IndexTaskLease task = lease();
        List<IndexStageProcessor> processors = new ArrayList<>();
        for (IndexStage stage : List.of(IndexStage.PARSING, IndexStage.CHUNKING, IndexStage.EMBEDDING)) {
            processors.add(
                new IndexStageProcessor() {
                    /** 声明该测试处理器模拟的索引阶段。 */
                    public IndexStage stage() {
                        return stage;
                    }

                    /** 执行本用例定义的阶段处理或模拟阶段结果。 */
                    public void process(IndexTaskContext context) {
                        if (stage == IndexStage.EMBEDDING) {
                            throw new IndexFailure("MODEL_503_UNAVAILABLE", "模型暂时不可用", true);
                        }
                    }
                }
            );
        }
        new IndexTaskWorker(store, processors).run(task);
        verify(store).fail(
            eq(task),
            argThat(failure -> failure.retryable() && failure.code().equals("MODEL_503_UNAVAILABLE"))
        );
        verify(store, never()).advance(task, IndexStage.EMBEDDING);
        verify(store, never()).advance(task, IndexStage.INDEXING);
    }

    /** 验证旧 Worker 不改变新尝试的状态。 */
    @Test
    void shouldDiscardStaleWorkerWithoutChangingNewAttempt() {
        IndexTaskStore store = mock(IndexTaskStore.class);
        IndexTaskLease task = lease();
        doThrow(new LostTaskLease()).when(store).checkActive(task);
        new IndexTaskWorker(store, List.of()).run(task);
        verify(store, never()).fail(any(), any());
        verify(store, never()).advance(any(), any());
    }

    /** 验证未预期异常脱敏，不暴露供应方或存储密钥。 */
    @Test
    void shouldSanitizeUnexpectedExceptionWithoutExposingProviderOrStorageSecrets() {
        IndexTaskStore store = mock(IndexTaskStore.class);
        IndexStageProcessor processor = new IndexStageProcessor() {
            /** 声明该测试处理器模拟的索引阶段。 */
            public IndexStage stage() {
                return IndexStage.PARSING;
            }

            /** 执行本用例定义的阶段处理或模拟阶段结果。 */
            public void process(IndexTaskContext context) {
                throw new IllegalArgumentException("private-storage-key");
            }
        };
        new IndexTaskWorker(store, List.of(processor)).run(lease());
        verify(store).fail(
            any(),
            argThat(
                failure ->
                    failure.code().equals("DOC_500_INDEX_FAILED") &&
                    !failure.getMessage().contains("private-storage-key")
            )
        );
    }

    /** 验证重复或终态阶段处理器注册被拒绝。 */
    @Test
    void shouldRejectDuplicateAndTerminalStageProcessors() {
        IndexStageProcessor parsing = mock(IndexStageProcessor.class);
        when(parsing.stage()).thenReturn(IndexStage.PARSING);
        assertThatThrownBy(() ->
            new IndexTaskWorker(mock(IndexTaskStore.class), List.of(parsing, parsing))
        ).isInstanceOf(IllegalArgumentException.class);
        when(parsing.stage()).thenReturn(IndexStage.COMPLETED);
        assertThatThrownBy(() -> new IndexTaskWorker(mock(IndexTaskStore.class), List.of(parsing))).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    /** 验证指数退避受上限约束且非法时长被拒绝。 */
    @Test
    void shouldCapExponentialBackoffAndRejectInvalidDurations() {
        IndexTaskProperties properties = new IndexTaskProperties(
            true,
            2,
            Duration.ofSeconds(1),
            Duration.ofMinutes(2),
            2,
            Duration.ofSeconds(5),
            Duration.ofSeconds(12),
            20
        );
        assertThat(properties.isValidDurations()).isTrue();
        assertThat(properties.backoffMillis(0)).isEqualTo(5000);
        assertThat(properties.backoffMillis(1)).isEqualTo(10000);
        assertThat(properties.backoffMillis(2)).isEqualTo(12000);
        assertThat(IndexStage.INDEXING.next()).isEqualTo(IndexStage.COMPLETED);
        assertThatThrownBy(IndexStage.COMPLETED::next).isInstanceOf(IllegalStateException.class);
        assertThat(
            new IndexTaskProperties(
                true,
                1,
                Duration.ZERO,
                Duration.ofSeconds(1),
                0,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                1
            ).isValidDurations()
        ).isFalse();
    }
}
