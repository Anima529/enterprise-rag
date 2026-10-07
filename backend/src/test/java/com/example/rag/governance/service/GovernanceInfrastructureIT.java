package com.example.rag.governance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.governance.config.GovernanceProperties;
import com.example.rag.governance.mapper.GovernanceMapper;
import com.example.rag.user.domain.UserAccount;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(
    properties = { "rag.seed.enabled=false", "rag.index-task.enabled=false", "rag.evaluation.enabled=false" }
)
class GovernanceInfrastructureIT {

    @Autowired
    StringRedisTemplate redis;

    /** 用户和部门窗口一起检查，另一个用户不能绕过部门限额。 */
    @Test
    void shouldAtomicallyEnforceUserAndDepartmentLimitsInRealRedis() {
        RedisCostGuard guard = guard(redis, 2, 3, 2, Duration.ofSeconds(2));
        UserAccount first = actor(1, 10);
        guard.request(first);
        guard.request(first);
        assertThatThrownBy(() -> guard.request(first)).isInstanceOf(BusinessException.class);
        guard.request(actor(2, 10));
        assertThatThrownBy(() -> guard.request(actor(3, 10))).isInstanceOf(BusinessException.class);
        guard.request(actor(4, 11));
    }

    /** 两个应用实例共享 Redis 许可，释放与失效租约都能恢复一个可用槽位。 */
    @Test
    void shouldShareModelPermitsAcrossInstancesAndRecoverExpiredLeases() throws Exception {
        GovernanceMapper mapper = namespace();
        GovernanceProperties config = configuration(2, 3, 1, Duration.ofMillis(80));
        RedisCostGuard first = new RedisCostGuard(redis, config, mapper);
        RedisCostGuard second = new RedisCostGuard(redis, config, mapper);
        RedisCostGuard.Permit permit = first.acquire("synthetic-model");
        assertThatThrownBy(() -> second.acquire("synthetic-model")).isInstanceOf(BusinessException.class);
        permit.close();
        RedisCostGuard.Permit abandoned = second.acquire("synthetic-model");
        Thread.sleep(150);
        try (RedisCostGuard.Permit recovered = first.acquire("synthetic-model")) {
            abandoned.close();
            assertThatThrownBy(() -> second.acquire("synthetic-model")).isInstanceOf(BusinessException.class);
        }
    }

    /** Redis 失联应明确停止收费调用，而非落回无限制的本机并发。 */
    @Test
    void shouldFailClosedWhenRedisCannotExecuteGovernanceScripts() {
        StringRedisTemplate unavailable = mock(StringRedisTemplate.class);
        when(
            unavailable.execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                anyList(),
                any(Object[].class)
            )
        ).thenThrow(new RedisConnectionFailureException("synthetic outage"));
        RedisCostGuard guard = guard(unavailable, 2, 3, 1, Duration.ofSeconds(2));
        assertThatThrownBy(() -> guard.acquire("synthetic-model"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("暂停模型调用");
        assertThatThrownBy(() -> guard.request(actor(1, 10))).isInstanceOf(BusinessException.class);
    }

    /** 为本次测试创建独立 Redis 命名空间，不清空其他应用的 Key。 */
    private GovernanceMapper namespace() {
        GovernanceMapper mapper = mock(GovernanceMapper.class);
        when(mapper.namespace()).thenReturn("test:" + UUID.randomUUID());
        return mapper;
    }

    /** 创建独立治理服务以验证真实脚本，而无需调用收费模型。 */
    private RedisCostGuard guard(
        StringRedisTemplate template,
        int user,
        int department,
        int concurrency,
        Duration lease
    ) {
        return new RedisCostGuard(template, configuration(user, department, concurrency, lease), namespace());
    }

    /** 合成预算保持窗口与条目有界，测试租约不影响生产配置。 */
    private GovernanceProperties configuration(int user, int department, int concurrency, Duration lease) {
        return new GovernanceProperties(
            true,
            user,
            department,
            Duration.ofSeconds(2),
            concurrency,
            lease,
            false,
            Duration.ofSeconds(2),
            2,
            0.995,
            10000
        );
    }

    /** 构造仅用于限流主体的合成账号，不保存或公开真实身份。 */
    private UserAccount actor(long id, long departmentId) {
        return new UserAccount(id, "synthetic", "", departmentId, "合成部门", "USER", "ACTIVE", false, "ACTIVE", false);
    }
}
