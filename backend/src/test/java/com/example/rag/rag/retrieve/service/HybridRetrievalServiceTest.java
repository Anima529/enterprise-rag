package com.example.rag.rag.retrieve.service;

import static com.example.rag.rag.retrieve.service.RetrievalAlgorithmsTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.AuthorizedScopeFixtures;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.gateway.ModelGateways.*;
import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.example.rag.rag.retrieve.repository.VectorSearchRepository;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class HybridRetrievalServiceTest {

    private final VectorSearchRepository vectors = mock(VectorSearchRepository.class);
    private final KeywordRetriever keywords = mock(KeywordRetriever.class);
    private final EmbeddingGateway embedding = mock(EmbeddingGateway.class);
    private final RerankerGateway reranker = mock(RerankerGateway.class);
    private HybridRetrievalService service;

    /** 为每个用例建立确定的两路候选与 Cross-Encoder 替身。 */
    @BeforeEach
    void setup() {
        ChatProperties chat = mock(ChatProperties.class);
        when(chat.minSimilarity()).thenReturn(.2);
        when(embedding.embed(anyList())).thenReturn(List.of(List.of(1f)));
        when(vectors.search(any(), anyList(), anyInt())).thenReturn(List.of(hit(1, 10, 1)));
        when(keywords.search(any(), anyString(), anyInt())).thenReturn(List.of(keyword(2, 20, 2, .3)));
        when(reranker.rerank(anyString(), anyList())).thenReturn(
            List.of(new RerankScore(1, .9), new RerankScore(0, .7))
        );
        service = new HybridRetrievalService(
            vectors,
            keywords,
            embedding,
            reranker,
            config(),
            chat,
            new RrfFusion(),
            new ParentSelectionPolicy(config())
        );
    }

    /** 用例结束释放本组创建的召回线程池。 */
    @AfterEach
    void cleanup() {
        service.shutdown();
    }

    /** 精排改变排名后仍保存向量、FTS 和 RRF 原分数。 */
    @Test
    void shouldRerankAndPreserveOriginalRetrievalScores() {
        HybridRetrievalService.Result result = retrieve();
        assertThat(result.ranked())
            .extracting(candidate -> candidate.hit().chunkId())
            .containsExactly(2L, 1L);
        assertThat(result.ranked().getFirst().keywordScore()).isEqualTo(.3);
        assertThat(result.rerankStatus()).isEqualTo("SUCCESS");
    }

    /** 向量失败时只使用原权限范围的关键词候选，精排失败回退 RRF。 */
    @Test
    void shouldFallbackToAuthorizedKeywordAndRrfWhenVectorAndRerankerFail() {
        when(embedding.embed(anyList())).thenThrow(new IllegalStateException("private error"));
        when(reranker.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("private error"));
        HybridRetrievalService.Result result = retrieve();
        assertThat(result.vectorStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.rerankStatus()).isEqualTo("FALLBACK_RRF");
        assertThat(result.selected())
            .extracting(candidate -> candidate.hit().chunkId())
            .containsExactly(2L);
    }

    /** 关键词故障不影响授权向量路线，两路故障返回明确业务错误。 */
    @Test
    void shouldUseVectorOnKeywordFailureAndRejectDualFailure() {
        when(keywords.search(any(), anyString(), anyInt())).thenThrow(new IllegalStateException());
        when(reranker.rerank(anyString(), anyList())).thenThrow(new IllegalStateException());
        assertThat(retrieve().selected())
            .extracting(candidate -> candidate.hit().chunkId())
            .containsExactly(1L);
        when(embedding.embed(anyList())).thenThrow(new IllegalStateException());
        assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, failure ->
            assertThat(failure.errorCode()).isEqualTo(ErrorCode.RAG_503_RETRIEVAL_UNAVAILABLE)
        );
    }

    /** 两路通过互相等待证明同时执行；串行召回会因屏障超时失败。 */
    @Test
    void shouldRunBothRoutesConcurrently() {
        CountDownLatch started = new CountDownLatch(2);
        when(vectors.search(any(), anyList(), anyInt())).thenAnswer(call -> {
            started.countDown();
            assertThat(started.await(200, TimeUnit.MILLISECONDS)).isTrue();
            return List.of(hit(1, 10, 1));
        });
        when(keywords.search(any(), anyString(), anyInt())).thenAnswer(call -> {
            started.countDown();
            assertThat(started.await(200, TimeUnit.MILLISECONDS)).isTrue();
            return List.of(keyword(2, 20, 2, .3));
        });
        assertThat(retrieve().vectorStatus()).isEqualTo("SUCCESS");
    }

    /** 一路阻塞时独立超时，另一授权路线继续提供候选。 */
    @Test
    void shouldTimeoutOnlyStalledRoute() {
        when(keywords.search(any(), anyString(), anyInt())).thenAnswer(call -> {
            Thread.sleep(2000);
            return List.of();
        });
        when(reranker.rerank(anyString(), anyList())).thenThrow(new IllegalStateException());
        HybridRetrievalService.Result result = retrieve();
        assertThat(result.keywordStatus()).isEqualTo("TIMEOUT");
        assertThat(result.selected()).hasSize(1);
    }

    /** 重复或越界的精排索引不得伪造候选，始终回退已存在的 RRF。 */
    @Test
    void shouldFallbackOnInvalidRerankIndexes() {
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(new RerankScore(99, .9)));
        assertThat(retrieve().rerankStatus()).isEqualTo("FALLBACK_RRF");
        when(reranker.rerank(anyString(), anyList())).thenReturn(
            List.of(new RerankScore(0, .9), new RerankScore(0, .8))
        );
        assertThat(retrieve().rerankStatus()).isEqualTo("FALLBACK_RRF");
    }

    /** SIMPLE 不调用 FTS 或精排；候选与最终预算按配置取得。 */
    @Test
    void shouldUseConfiguredSimpleBudgetWithoutKeywordOrReranker() {
        HybridRetrievalService.Result result = service.retrieve(
            AuthorizedScopeFixtures.issue(1, 1, 1),
            "年假",
            RetrievalProperties.Complexity.SIMPLE
        );
        assertThat(result.keywordStatus()).isEqualTo("SKIPPED");
        verify(vectors).search(any(), anyList(), eq(8));
        verifyNoInteractions(keywords, reranker);
    }

    /** 以合法授权范围执行 STANDARD，避免用例建立无权限检索入口。 */
    private HybridRetrievalService.Result retrieve() {
        return service.retrieve(AuthorizedScopeFixtures.issue(1, 1, 1), "年假");
    }
}
