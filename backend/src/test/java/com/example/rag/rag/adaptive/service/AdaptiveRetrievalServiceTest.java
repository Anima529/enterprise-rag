package com.example.rag.rag.adaptive.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.rag.common.security.AuthorizedScopeFixtures;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.conversation.mapper.ConversationMapper;
import com.example.rag.document.service.chunk.ChunkTokenizer;
import com.example.rag.rag.adaptive.config.AdaptiveProperties;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.prompt.support.PromptFixtures;
import com.example.rag.rag.retrieve.config.RetrievalProperties;
import com.example.rag.rag.retrieve.config.RetrievalProperties.*;
import com.example.rag.rag.retrieve.service.HybridRetrievalService;
import com.example.rag.rag.retrieve.service.ParentSelectionPolicy;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

class AdaptiveRetrievalServiceTest {

    private final StructuredModelService models = mock(StructuredModelService.class);
    private final ConversationMapper messages = mock(ConversationMapper.class);
    private final HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
    private final ParentSelectionPolicy parents = mock(ParentSelectionPolicy.class);
    private final ObjectMapper json = new ObjectMapper();
    private final RetrievalScope scope = AuthorizedScopeFixtures.issue(1, 2, 3);
    private AdaptiveRetrievalService service;

    /** 建立小而确定的规划预算，不依赖外部模型或真实 SQL。 */
    @BeforeEach
    void setup() throws Exception {
        ChatProperties chat = mock(ChatProperties.class);
        when(chat.questionTokens()).thenReturn(1024);
        RetrievalProperties budgets = mock(RetrievalProperties.class);
        when(budgets.defaultComplexity()).thenReturn(Complexity.STANDARD);
        when(budgets.rrfK()).thenReturn(60);
        when(budgets.profile(any())).thenReturn(new Profile(20, 20, 40, 5));
        AdaptiveProperties config = new AdaptiveProperties(
            true,
            3,
            1200,
            3,
            4,
            512,
            Duration.ofSeconds(1),
            Duration.ofSeconds(4),
            Duration.ofSeconds(5)
        );
        service = new AdaptiveRetrievalService(
            models,
            messages,
            retrieval,
            new ChunkTokenizer(),
            config,
            chat,
            budgets,
            parents,
            json,
            PromptFixtures.catalog()
        );
        when(messages.history(anyLong(), anyLong(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        response("ROUTE", "{\"route\":\"STANDARD\"}");
    }

    /** SIMPLE 与 STANDARD 返回稳定原因码及独立查询。 */
    @Test
    void shouldRouteSimpleAndStandardWithStableReasons() throws Exception {
        response("ROUTE", "{\"route\":\"SIMPLE\"}");
        assertThat(plan("年假多少天？").route()).isEqualTo(Complexity.SIMPLE);
        response("ROUTE", "{\"route\":\"STANDARD\"}");
        assertThat(plan("解释年假规则").reasonCode()).isEqualTo("MODEL_STANDARD");
    }

    /** 本人上一轮补齐省略问题，模型输入和 SQL 参数均限定原授权范围。 */
    @Test
    void shouldRewriteEllipsisUsingOnlyBoundedOwnedHistory() throws Exception {
        when(messages.history(anyLong(), anyLong(), anyString(), anyString(), anyInt())).thenReturn(
            List.of(
                new ConversationMapper.History("ASSISTANT", "工作一年年假五天。[C1]"),
                new ConversationMapper.History("USER", "工作一年的年假是多少？")
            )
        );
        response("REWRITE", "{\"query\":\"工作五年的员工年假是多少？\"}");
        assertThat(plan("那工作五年的呢？").query()).isEqualTo("工作五年的员工年假是多少？");
        verify(messages).history(9, 1, "request", "[2]", 6);
        verify(models).call(eq("REWRITE"), anyString(), contains("工作一年"), eq(512), any());
    }

    /** Rewrite 失败保留原问题，仍可完成后续 Router。 */
    @Test
    void shouldKeepOriginalQuestionOnRewriteFailure() {
        when(messages.history(anyLong(), anyLong(), anyString(), anyString(), anyInt())).thenReturn(
            List.of(
                new ConversationMapper.History("ASSISTANT", "年假五天。"),
                new ConversationMapper.History("USER", "年假？")
            )
        );
        when(models.call(eq("REWRITE"), anyString(), anyString(), anyInt(), any())).thenThrow(
            new IllegalStateException()
        );
        assertThat(plan("那五年呢？").query()).isEqualTo("那五年呢？");
        assertThat(plan("那五年呢？").fallback()).isEqualTo("REWRITE_FAILED");
    }

    /** 非法路线与模型超时都必须回退 STANDARD。 */
    @Test
    void shouldFallbackToStandardOnInvalidOrTimedOutRouter() throws Exception {
        response("ROUTE", "{\"route\":\"ALL_DATABASES\"}");
        assertThat(plan("年假").reasonCode()).isEqualTo("FALLBACK_STANDARD");
        when(models.call(eq("ROUTE"), anyString(), anyString(), anyInt(), any())).thenThrow(
            new IllegalStateException()
        );
        assertThat(plan("年假").fallback()).isEqualTo("ROUTER_FAILED");
    }

    /** HyDE 仅替换向量查询，真实原问题和子问题继续作为词项与精排查询。 */
    @Test
    void shouldKeepHydeOutsideEvidenceAndReuseScopeForAllQueries() throws Exception {
        complex();
        when(retrieval.retrieve(any(), anyString(), anyString(), any(), any())).thenReturn(
            new HybridRetrievalService.Result(List.of(), List.of(), List.of(), "SUCCESS", "SUCCESS", "SKIPPED", 1, 1, 0)
        );
        when(parents.select(anyList(), anyInt())).thenReturn(List.of());
        AdaptiveRetrievalService.Outcome outcome = service.retrieve(scope, 9, "request", "对比年假和报销");
        assertThat(outcome.plan().route()).isEqualTo(Complexity.COMPLEX);
        assertThat(outcome.searches()).hasSize(3);
        verify(retrieval).retrieve(
            same(scope),
            eq("对比年假和报销"),
            eq("假设年假制度片段"),
            eq(Complexity.COMPLEX),
            any()
        );
        verify(retrieval).retrieve(same(scope), eq("年假规则"), eq("年假规则"), eq(Complexity.COMPLEX), any());
        assertThat(outcome.selected()).isEmpty();
    }

    /** 拆解数量超限不执行 HyDE，也不进行无界模型调用。 */
    @Test
    void shouldRejectTooManySubqueriesAndFallback() throws Exception {
        complex();
        response("DECOMPOSE", "{\"queries\":[\"一\",\"二\",\"三\",\"四\"]}");
        assertThat(plan("对比").route()).isEqualTo(Complexity.STANDARD);
        verify(models, never()).call(eq("HYDE"), anyString(), anyString(), anyInt(), any());
    }

    /** HyDE 故障必须回退完整 STANDARD 计划。 */
    @Test
    void shouldFallbackWhenHydeFails() throws Exception {
        complex();
        when(models.call(eq("HYDE"), anyString(), anyString(), anyInt(), any())).thenThrow(new IllegalStateException());
        assertThat(plan("对比").fallback()).isEqualTo("COMPLEX_PLAN_FAILED");
    }

    /** 已取消线程不能借助故障回退继续规划。 */
    @Test
    void shouldPropagateCancellationInsteadOfPlanningFallback() {
        when(models.call(eq("ROUTE"), anyString(), anyString(), anyInt(), any())).thenThrow(
            new IllegalStateException()
        );
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> plan("年假")).isInstanceOf(IllegalStateException.class);
        } finally {
            Thread.interrupted();
        }
    }

    /** 完整轮次超过预算时连同短回答一起排除，不能保留孤立的历史消息。 */
    @Test
    void shouldExcludeOversizedHistoryFromRewrite() {
        when(messages.history(anyLong(), anyLong(), anyString(), anyString(), anyInt())).thenReturn(
            List.of(
                new ConversationMapper.History("ASSISTANT", "年假五天。"),
                new ConversationMapper.History("USER", "年假".repeat(3000))
            )
        );
        plan("年假");
        verify(models, never()).call(eq("REWRITE"), anyString(), anyString(), anyInt(), any());
    }

    /** 构造合法复杂计划的模型替身，仅用于验证编排。 */
    private void complex() throws Exception {
        response("ROUTE", "{\"route\":\"COMPLEX\"}");
        response("DECOMPOSE", "{\"queries\":[\"年假规则\",\"报销规则\"]}");
        response("HYDE", "{\"passage\":\"假设年假制度片段\"}");
    }

    /** 为指定阶段提供严格 JSON，保持模型输出与测试输入分离。 */
    private void response(String stage, String body) throws Exception {
        when(models.call(eq(stage), anyString(), anyString(), anyInt(), any())).thenReturn(json.readTree(body));
    }

    /** 使用固定本人会话与已签发范围建立计划。 */
    private AdaptiveRetrievalService.Plan plan(String question) {
        return service.plan(scope, 9, "request", question);
    }
}
