package com.example.rag.evaluation.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.evaluation.dto.EvaluationModels.*;
import com.example.rag.evaluation.service.EvaluationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/admin/evaluation")
public class EvaluationController {

    private final EvaluationService service;

    /** 评估入口只绑定参数，权限与执行交给 Service。 */
    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    /** 查询最近数据集，普通用户由 Service 拒绝。 */
    @GetMapping("/datasets")
    public ApiResponse<List<Map<String, Object>>> datasets() {
        List<Map<String, Object>> datasets = service.datasets();
        return ApiResponse.success(datasets);
    }

    /** 原子建立固定评估身份和不可变案例集合。 */
    @PostMapping("/datasets")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody DatasetRequest request) {
        long id = service.create(request);
        return ApiResponse.success(Map.of("id", id));
    }

    /** 查看数据集原始问题、相关 Child 和历史快照。 */
    @GetMapping("/datasets/{id}")
    public ApiResponse<Map<String, Object>> dataset(@Positive @PathVariable long id) {
        Map<String, Object> dataset = service.dataset(id);
        return ApiResponse.success(dataset);
    }

    /** 创建异步实验，冻结当前身份、文档、模型和预算配置。 */
    @PostMapping("/datasets/{id}/runs")
    public ApiResponse<Map<String, Long>> start(
        @Positive @PathVariable long id,
        @Valid @RequestBody RunRequest request
    ) {
        long runId = service.start(id, request);
        return ApiResponse.success(Map.of("id", runId));
    }

    /** 列出同一数据集的实验汇总，允许公平比较检索模式。 */
    @GetMapping("/datasets/{id}/runs")
    public ApiResponse<List<Map<String, Object>>> runs(@Positive @PathVariable long id) {
        List<Map<String, Object>> runs = service.runs(id);
        return ApiResponse.success(runs);
    }

    /** 返回一次实验的配置、每题排序、指标和脱敏错误。 */
    @GetMapping("/runs/{id}")
    public ApiResponse<Map<String, Object>> run(@Positive @PathVariable long id) {
        Map<String, Object> run = service.run(id);
        return ApiResponse.success(run);
    }
}
