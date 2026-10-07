package com.example.rag.governance.service;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.governance.config.GovernanceProperties;
import com.example.rag.governance.mapper.GovernanceMapper;
import com.example.rag.user.domain.UserAccount;
import java.util.*;
import org.slf4j.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 使用 Redis 原子脚本限制用户、部门和跨实例模型并发；不可用时停止新的收费调用。 */
@Service
public class RedisCostGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisCostGuard.class);
    private final StringRedisTemplate redis;
    private final GovernanceProperties config;
    private final String prefix;
    private final DefaultRedisScript<Long> rate = script("lua/request-rate.lua");
    private final DefaultRedisScript<Long> model = script("lua/model-permit.lua");

    /** 按数据库和 schema 隔离治理 Key，避免验收影响开发实例的计数。 */
    public RedisCostGuard(StringRedisTemplate redis, GovernanceProperties config, GovernanceMapper mapper) {
        this.redis = redis;
        this.config = config;
        String namespace = mapper.namespace();
        prefix = "rag:governance:{" + ScopeFingerprint.digest(namespace) + "}:";
    }

    /** 原子检查两级窗口，拒绝时不增加任何窗口的计数。 */
    public void request(UserAccount actor) {
        if (!config.enabled()) {
            return;
        }
        try {
            Long accepted = redis.execute(
                rate,
                List.of(prefix + "user:" + actor.id(), prefix + "department:" + actor.departmentId()),
                Integer.toString(config.userRequests()),
                Integer.toString(config.departmentRequests()),
                Long.toString(config.window().toMillis())
            );
            if (!Long.valueOf(1).equals(accepted)) {
                throw new BusinessException(ErrorCode.RAG_429_RATE_LIMIT);
            }
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.RAG_503_GOVERNANCE_UNAVAILABLE);
        }
    }

    /** 模型许可使用唯一租约，进程中断后自动到期；租约时长须大于物理调用的最大超时。 */
    public Permit acquire(String modelName) {
        if (!config.enabled()) {
            return new Permit(null, null);
        }
        String key = prefix + "model:" + ScopeFingerprint.digest(modelName);
        String owner = UUID.randomUUID().toString();
        try {
            Long accepted = redis.execute(
                model,
                List.of(key),
                Integer.toString(config.modelConcurrency()),
                Long.toString(config.modelLease().toMillis()),
                owner
            );
            if (!Long.valueOf(1).equals(accepted)) {
                throw new BusinessException(ErrorCode.RAG_429_BUSY);
            }
            return new Permit(key, owner);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException(ErrorCode.RAG_503_GOVERNANCE_UNAVAILABLE);
        }
    }

    /** 启动时确认租约覆盖物理请求总时限，防止仍在执行的调用被提前当作空闲。 */
    public void validateTimeout(java.time.Duration timeout) {
        if (config.enabled() && config.modelLease().compareTo(timeout) <= 0) {
            throw new IllegalArgumentException("Model lease must exceed physical call timeout");
        }
    }

    /** 从资源读取原子脚本，脚本正文不分散在业务方法。 */
    private DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public final class Permit implements AutoCloseable {

        private final String key;
        private final String owner;
        private boolean closed;

        /** 保存当前物理调用唯一许可，禁用模式无需访问 Redis。 */
        private Permit(String key, String owner) {
            this.key = key;
            this.owner = owner;
        }

        /** 仅释放自己的租约；Redis 失联依靠到期回收，不能覆盖业务异常。 */
        @Override
        public synchronized void close() {
            if (closed || key == null) {
                return;
            }
            closed = true;
            try {
                redis.opsForZSet().remove(key, owner);
            } catch (RuntimeException failure) {
                log.warn(
                    "model permit release deferred to lease expiry, exceptionType={}",
                    failure.getClass().getSimpleName()
                );
            }
        }
    }
}
