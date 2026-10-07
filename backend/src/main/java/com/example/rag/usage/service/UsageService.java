package com.example.rag.usage.service;

import static com.example.rag.usage.dto.UsageModels.*;

import com.example.rag.common.api.TraceContextKeys;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.document.exception.index.IndexFailure;
import com.example.rag.rag.model.domain.ModelFailureCode;
import com.example.rag.usage.config.UsageProperties;
import com.example.rag.usage.domain.UsageProtocol;
import com.example.rag.usage.repository.UsageRepository;
import com.example.rag.user.domain.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 实际调用使用唯一 callId；失败、取消与已完成回调共享同一记账对象。 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private final UsageProperties config;
    private final UsageRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final PermissionScopeService permissions;

    /** 配置历史账本、可重建的 Redis 快照及当前用户数据范围。 */
    public UsageService(
        UsageRepository repository,
        StringRedisTemplate redis,
        ObjectMapper json,
        PermissionScopeService permissions,
        UsageProperties config
    ) {
        this.repository = repository;
        this.redis = redis;
        this.json = json;
        this.permissions = permissions;
        this.config = config;
    }

    /** 在实际模型调用前固定身份与价格版本；未关联的诊断调用只计入管理员范围。 */
    public Call begin(String stage, String model) {
        String requestId = MDC.get(TraceContextKeys.REQUEST_ID);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        String traceId = MDC.get(TraceContextKeys.TRACE_ID);
        Identity identity = repository.identity(requestId, MDC.get(TraceContextKeys.USAGE_DOCUMENT_ID));
        return new Call(
            requestId,
            traceId == null ? requestId : traceId,
            stage,
            model,
            identity,
            repository.price(model)
        );
    }

    /** 未知计量不编造 Token，CALL 与 UNITS 价格分别使用真实调用和业务单位。 */
    public static BigDecimal estimate(Price price, Long input, Long output, Long units) {
        if (price == null) {
            return null;
        }
        return switch (price.basis()) {
            case UsageProtocol.CALL -> price.inputPrice();
            case UsageProtocol.UNITS -> units == null ? null : price.inputPrice().multiply(BigDecimal.valueOf(units));
            case UsageProtocol.TOKENS -> tokenCost(price, input, output);
            default -> throw new IllegalArgumentException("Unknown price basis");
        };
    }

    /** 输入或输出单价为零时无需该侧计量，其余缺失则无法完整估算。 */
    private static BigDecimal tokenCost(Price price, Long input, Long output) {
        if (
            (input == null && price.inputPrice().signum() > 0) || (output == null && price.outputPrice().signum() > 0)
        ) {
            return null;
        }
        BigDecimal inputCost = price.inputPrice().multiply(BigDecimal.valueOf(input == null ? 0 : input));
        BigDecimal outputCost = price.outputPrice().multiply(BigDecimal.valueOf(output == null ? 0 : output));
        return inputCost
            .add(outputCost)
            .divide(
                BigDecimal.valueOf(UsageProtocol.TOKENS_PER_PRICE_UNIT),
                UsageProtocol.COST_DECIMAL_SCALE,
                RoundingMode.HALF_UP
            );
    }

    /** 看板直接查询历史来源，普通用户范围不接受客户端指定。 */
    public Map<String, Object> dashboard(LocalDate from, LocalDate to, boolean management) {
        UserAccount actor = permissions.currentUser();
        Long userId = management ? null : actor.id();
        Instant start = from.atStartOfDay(config.timezone()).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(config.timezone()).toInstant();
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totals", repository.totals(userId, start, end));
        dashboard.put("stages", repository.breakdown(userId, start, end, "stage"));
        dashboard.put("models", repository.breakdown(userId, start, end, "model"));
        dashboard.put("daily", repository.breakdown(userId, start, end, "day"));
        dashboard.put("routes", repository.routes(userId, start, end));
        dashboard.put("currency", UsageProtocol.CURRENCY);
        dashboard.put("from", from);
        dashboard.put("to", to);
        dashboard.put("scope", management ? UsageProtocol.ALL : UsageProtocol.SELF);
        return dashboard;
    }

    /** 从 PostgreSQL 重建当前日期实时快照，只写本功能命名空间且设置过期。 */
    public Map<String, Object> rebuild(Long userId) {
        LocalDate date = LocalDate.now(config.timezone());
        Instant from = date.atStartOfDay(config.timezone()).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(config.timezone()).toInstant();
        Map<String, Object> totals = repository.totals(userId, from, to);
        Map<String, Object> response = new LinkedHashMap<>(totals);
        try {
            String script = """
            local old=redis.call('GET',KEYS[1])
            if old and tonumber(cjson.decode(old).calls)>tonumber(cjson.decode(ARGV[1]).calls) then
                return 0
            end
            redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2])
            return 1
            """;
            redis.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                List.of(cacheKey(userId, date)),
                json.writeValueAsString(totals),
                Long.toString(config.realtimeTtl().toSeconds())
            );
            response.put("redisStatus", UsageProtocol.REBUILT);
        } catch (Exception failure) {
            log.warn("usage realtime unavailable, exceptionType={}", failure.getClass().getSimpleName());
            response.put("redisStatus", UsageProtocol.UNAVAILABLE);
        }
        return response;
    }

    /** 使用独立日期和用户维度生成实时统计 Key。 */
    public String cacheKey(Long userId, LocalDate date) {
        return "rag:usage:v19:" + repository.cacheNamespace() + ":" + (userId == null ? "all" : userId) + ":" + date;
    }

    public final class Call {

        private final String requestId;
        private final String traceId;
        private final String stage;
        private final String model;
        private final Identity identity;
        private final Price price;
        private final String callId = UUID.randomUUID().toString();
        private final long started = System.nanoTime();
        private final AtomicBoolean recorded = new AtomicBoolean();
        private Long input;
        private Long output;
        private Long units;

        /** 将一次实际调用的身份、模型和价格固定为快照。 */
        private Call(String requestId, String traceId, String stage, String model, Identity identity, Price price) {
            this.requestId = requestId;
            this.traceId = traceId;
            this.stage = stage;
            this.model = model;
            this.identity = identity;
            this.price = price;
        }

        /** 累计流式响应中的供应方总用量，重复累计帧取最大值而非求和。 */
        public synchronized void tokens(Long input, Long output) {
            if (input != null && input >= 0) {
                this.input = this.input == null ? input : Math.max(this.input, input);
            }
            if (output != null && output >= 0) {
                this.output = this.output == null ? output : Math.max(this.output, output);
            }
        }

        /** 保存供应方可确认的非 Token 计费单位。 */
        public synchronized void units(Long units) {
            this.units = units;
        }

        /** 只登记一次实际调用终态，未收到计量的失败或取消保持 NULL。 */
        public synchronized void finish(boolean success, Throwable failure) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            String error = null;
            if (!success) {
                error =
                    failure instanceof BusinessException business
                        ? business.errorCode().name()
                        : ModelFailureCode.MODEL_FAILED;
                if (failure instanceof IndexFailure index) {
                    error = index.code();
                }
                if (failure == null) {
                    error = UsageProtocol.CANCELLED;
                }
            }
            repository.insert(
                new CallRow(
                    requestId,
                    traceId,
                    callId,
                    identity,
                    stage,
                    model,
                    input,
                    output,
                    units,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    success,
                    error,
                    price,
                    estimate(price, input, output, units)
                )
            );
            rebuild(identity.userId());
            if (identity.userId() != null) {
                rebuild(null);
            }
        }
    }
}
