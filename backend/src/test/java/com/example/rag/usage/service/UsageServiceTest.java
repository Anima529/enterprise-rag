package com.example.rag.usage.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.usage.config.UsageProperties;
import com.example.rag.usage.dto.UsageModels.*;
import com.example.rag.usage.repository.UsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

class UsageServiceTest {

    private final UsageRepository repository = mock(UsageRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UsageService service = new UsageService(
        repository,
        redis,
        new ObjectMapper(),
        mock(PermissionScopeService.class),
        new UsageProperties(java.time.ZoneId.of("Asia/Shanghai"), java.time.Duration.ofDays(2), 366)
    );

    /** 每百万 Token 价格使用 BigDecimal，未知输入或输出不能按零计费。 */
    @Test
    void shouldEstimateTokensAndPreserveUnknownMetering() {
        Price price = price("TOKENS", "2", "6");
        assertThat(UsageService.estimate(price, 1000L, 500L, null)).isEqualByComparingTo("0.005");
        assertThat(UsageService.estimate(price, null, 500L, null)).isNull();
        assertThat(UsageService.estimate(price, 1000L, null, null)).isNull();
        assertThat(UsageService.estimate(null, 1000L, 500L, null)).isNull();
    }

    /** 非 Token 计费保留真实调用单位，没有单位时成本未知。 */
    @Test
    void shouldEstimateCallsAndReportedUnitsWithoutInventedTokens() {
        assertThat(UsageService.estimate(price("CALL", "0.01", "0"), null, null, null)).isEqualByComparingTo("0.01");
        assertThat(UsageService.estimate(price("UNITS", "0.01", "0"), null, null, 3L)).isEqualByComparingTo("0.03");
        assertThat(UsageService.estimate(price("UNITS", "0.01", "0"), null, null, null)).isNull();
    }

    /** 输出无费用的 Embedding 只需输入计量，不伪造输出 Token。 */
    @Test
    void shouldAllowZeroPricedSideWithoutTokenCount() {
        assertThat(UsageService.estimate(price("TOKENS", "1", "0"), 1000L, null, null)).isEqualByComparingTo("0.001");
    }

    /** 取消、错误与完成竞争只插入一条调用，重复累计帧不会相加。 */
    @Test
    void shouldRecordOnlyOneTerminalCallWithCumulativeTokenMaximum() {
        fixture();
        UsageService.Call call = service.begin("GENERATION", "test-model");
        call.tokens(20L, 4L);
        call.tokens(20L, 8L);
        call.finish(false, null);
        call.finish(true, null);
        ArgumentCaptor<CallRow> captured = ArgumentCaptor.forClass(CallRow.class);
        verify(repository, times(1)).insert(captured.capture());
        assertThat(captured.getValue().inputTokens()).isEqualTo(20);
        assertThat(captured.getValue().outputTokens()).isEqualTo(8);
        assertThat(captured.getValue().success()).isFalse();
        assertThat(captured.getValue().errorCode()).isEqualTo("CANCELLED");
    }

    /** 多次实际重试必须各有 callId，不能合并或重复计为同一调用。 */
    @Test
    void shouldGiveEachPhysicalRetryAnIndependentCallId() {
        fixture();
        service.begin("EMBEDDING", "test-model").finish(false, new IllegalStateException());
        service.begin("EMBEDDING", "test-model").finish(true, null);
        ArgumentCaptor<CallRow> captured = ArgumentCaptor.forClass(CallRow.class);
        verify(repository, times(2)).insert(captured.capture());
        assertThat(captured.getAllValues()).extracting(CallRow::callId).doesNotHaveDuplicates();
    }

    /** Redis 不可用仍完成历史插入，重建响应显式报告故障。 */
    @Test
    void shouldKeepHistoricalLedgerWhenRedisIsUnavailable() {
        fixture();
        when(redis.execute(any(), anyList(), anyString(), anyString())).thenThrow(new IllegalStateException());
        service.begin("RERANK", "test-model").finish(true, null);
        verify(repository).insert(any());
        assertThat(service.rebuild(null).get("redisStatus")).isEqualTo("UNAVAILABLE");
    }

    /** 配置身份与空聚合替身，账本测试不需要外部基础设施。 */
    private void fixture() {
        when(repository.identity(anyString(), isNull())).thenReturn(new Identity(1L, 2L, null, null));
        when(repository.totals(any(), any(), any())).thenReturn(Map.of("calls", 0, "last_id", 0));
    }

    /** 创建固定版本价格，单价仅用于确定性算法测试。 */
    private Price price(String basis, String input, String output) {
        return new Price(
            1,
            "test-model",
            "test-v1",
            basis,
            new BigDecimal(input),
            new BigDecimal(output),
            "CNY",
            Instant.EPOCH
        );
    }
}
