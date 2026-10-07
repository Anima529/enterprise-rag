package com.example.rag.usage.repository;

import static com.example.rag.usage.dto.UsageModels.*;

import com.example.rag.usage.mapper.UsageMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 以 MyBatis-Plus Mapper 保存实际调用，Redis 丢失不会影响 PostgreSQL 历史账本。 */
@Repository
public class UsageRepository {

    private final UsageMapper mapper;

    /** 注入 XML Mapper，模型与网络调用不进入此持久化边界。 */
    public UsageRepository(UsageMapper mapper) {
        this.mapper = mapper;
    }

    /** 统计缓存按数据库和 schema 隔离，验收不覆盖开发业务快照。 */
    public String cacheNamespace() {
        return mapper.cacheNamespace();
    }

    /** 读取调用时生效的价格快照，不为未知模型猜测价格。 */
    public Price price(String model) {
        List<Price> prices = mapper.price(model);
        return prices.isEmpty() ? null : prices.getFirst();
    }

    /** 为管理员读取全部不可变价格版本。 */
    public List<Map<String, Object>> prices() {
        return mapper.prices();
    }

    /** 新增价格版本，冲突不修改已有历史。 */
    public void addPrice(PriceRequest price) {
        mapper.addPrice(price);
    }

    /** 从消息或索引文档恢复调用身份，异步线程不依赖 Sa-Token。 */
    public Identity identity(String requestId, String documentId) {
        List<Identity> identities;
        if (documentId != null) {
            identities = mapper.documentIdentity(Long.parseLong(documentId));
        } else {
            identities = mapper.requestIdentity(requestId);
        }
        return identities.isEmpty() ? new Identity(null, null, null, null) : identities.getFirst();
    }

    /** 按请求、阶段和实际调用 ID 幂等保存，取消与完成回调不重复记账。 */
    public void insert(CallRow call) {
        Price price = call.price();
        Long priceId = price == null ? null : price.id();
        String priceVersion = price == null ? null : price.version();
        mapper.insert(call, priceId, priceVersion);
    }

    /** 汇总指定日期与用户范围的用量，未知成本单独计数。 */
    public Map<String, Object> totals(Long userId, Instant from, Instant to) {
        return mapper.totals(userId, from, to);
    }

    /** 先校验统计维度，再由 XML choose 选择固定表达式，禁止动态 SQL 列名。 */
    public List<Map<String, Object>> breakdown(Long userId, Instant from, Instant to, String dimension) {
        if (!Set.of("stage", "model", "day").contains(dimension)) {
            throw new IllegalArgumentException("Invalid usage dimension");
        }
        return mapper.breakdown(userId, from, to, dimension);
    }

    /** 查询请求终态、缓存命中与路线分布，活动请求单独可见。 */
    public List<Map<String, Object>> routes(Long userId, Instant from, Instant to) {
        return mapper.routes(userId, from, to);
    }

    /** 登记请求开始，相同 requestId 不重复创建 trace。 */
    public void startRequest(String requestId, String traceId, long messageId, long userId, long departmentId) {
        mapper.startRequest(requestId, traceId, messageId, userId, departmentId);
    }

    /** 将活动请求推进到单一终态，保留路线与首 Token 延迟。 */
    public void finishRequest(String requestId, String route, String reason, String status, long latency, Long first) {
        mapper.finishRequest(requestId, route, reason, status, latency, first);
    }

    /** 在既有消息终态事务中记录本次缓存复用标记。 */
    public void markCacheHit(String requestId, boolean cacheHit) {
        mapper.markCacheHit(requestId, cacheHit);
    }

    /** 汇总实际模型成本，只要存在未知计量就保持整体未知。 */
    public Map<String, Object> requestCost(String requestId) {
        return mapper.requestCost(requestId);
    }
}
