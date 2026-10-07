package com.example.rag.usage.mapper;

import com.example.rag.usage.dto.UsageModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 用量账本与聚合查询契约，用户条件和维度白名单在 XML 中固定。 */
@Mapper
public interface UsageMapper {
    /** 读取统计缓存的数据库/schema 命名空间。 */
    String cacheNamespace();

    /** 读取调用时最近的有效价格，不存在时返回空列表。 */
    List<Price> price(@Param("model") String model);

    /** 列出不可变价格版本供管理员核对。 */
    List<Map<String, Object>> prices();

    /** 只新增价格版本，唯一约束冲突由既有异常处理传播。 */
    int addPrice(@Param("price") PriceRequest price);

    /** 从索引文档归属查询实际调用身份，会话字段保持为空。 */
    List<Identity> documentIdentity(@Param("documentId") long documentId);

    /** 从助手消息查询请求身份，不依赖异步线程认证上下文。 */
    List<Identity> requestIdentity(@Param("requestId") String requestId);

    /** 按请求/阶段/实际调用 ID 幂等记账，未知价格保留 NULL。 */
    int insert(@Param("call") CallRow call, @Param("priceId") Long priceId, @Param("priceVersion") String priceVersion);

    /** 按日期与用户范围汇总总量，单独统计未知成本。 */
    Map<String, Object> totals(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /** 使用 XML 白名单选择阶段、模型或日期维度，不拼接动态列名。 */
    List<Map<String, Object>> breakdown(
        @Param("userId") Long userId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("dimension") String dimension
    );

    /** 查询请求终态、缓存命中和路线延迟分布。 */
    List<Map<String, Object>> routes(
        @Param("userId") Long userId,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    /** 幂等登记问答请求开始，不重复创建 trace。 */
    int startRequest(
        @Param("requestId") String requestId,
        @Param("traceId") String traceId,
        @Param("messageId") long messageId,
        @Param("userId") long userId,
        @Param("departmentId") long departmentId
    );

    /** 仅将运行中请求推进到单一终态。 */
    int finishRequest(
        @Param("requestId") String requestId,
        @Param("route") String route,
        @Param("reason") String reason,
        @Param("status") String status,
        @Param("latency") long latency,
        @Param("first") Long first
    );

    /** 在消息终态事务中记录本次实际缓存命中。 */
    int markCacheHit(@Param("requestId") String requestId, @Param("cacheHit") boolean cacheHit);

    /** 汇总请求实际成本，只要存在未知计量就保持整体未知。 */
    Map<String, Object> requestCost(@Param("requestId") String requestId);
}
