package com.example.rag.usage.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.usage.config.UsageProperties;
import com.example.rag.usage.dto.UsageModels.PriceRequest;
import com.example.rag.usage.repository.UsageRepository;
import com.example.rag.usage.service.UsageService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class UsageController {

    private final UsageService service;
    private final UsageRepository repository;
    private final UsageProperties config;

    /** 配置按身份隔离的统计与管理员价格版本管理。 */
    public UsageController(UsageService service, UsageRepository repository, UsageProperties config) {
        this.service = service;
        this.repository = repository;
        this.config = config;
    }

    /** 普通用户只读取本人用量，日期跨度限定一年。 */
    @GetMapping("/api/v1/usage/dashboard")
    public ApiResponse<Map<String, Object>> mine(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        validateDates(from, to);
        Map<String, Object> dashboard = service.dashboard(from, to, false);
        return ApiResponse.success(dashboard);
    }

    /** 管理员读取全局用量、分阶段成本、P95、错误和路线分布。 */
    @GetMapping("/api/v1/admin/usage/dashboard")
    public ApiResponse<Map<String, Object>> all(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        validateDates(from, to);
        Map<String, Object> dashboard = service.dashboard(from, to, true);
        return ApiResponse.success(dashboard);
    }

    /** 管理员从数据库重建实时聚合，Redis 故障时返回可见状态。 */
    @PostMapping("/api/v1/admin/usage/rebuild")
    public ApiResponse<Map<String, Object>> rebuild() {
        Map<String, Object> realtime = service.rebuild(null);
        return ApiResponse.success(realtime);
    }

    /** 展示管理员确认过的估算价格及版本，不包含凭证。 */
    @GetMapping("/api/v1/admin/usage/prices")
    public ApiResponse<List<Map<String, Object>>> prices() {
        List<Map<String, Object>> prices = repository.prices();
        return ApiResponse.success(prices);
    }

    /** 新价格只对后续调用生效，历史估算与原价格版本保持一致。 */
    @PostMapping("/api/v1/admin/usage/prices")
    public ApiResponse<Void> price(@Valid @RequestBody PriceRequest request) {
        try {
            repository.addPrice(request);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new BusinessException(ErrorCode.USAGE_409_PRICE_EXISTS);
        }
        return ApiResponse.success(null);
    }

    /** 拒绝反向区间与无界聚合查询。 */
    private void validateDates(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > config.maxQueryDays()) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
    }
}
