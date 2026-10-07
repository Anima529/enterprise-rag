package com.example.rag.evaluation.service;

import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.common.security.RetrievalScope;
import com.example.rag.evaluation.config.EvaluationProperties;
import com.example.rag.evaluation.domain.EvaluationProtocol;
import com.example.rag.evaluation.dto.EvaluationModels.*;
import com.example.rag.evaluation.mapper.EvaluationMapper;
import com.example.rag.evaluation.repository.EvaluationRepository;
import com.example.rag.governance.service.RagConfigurationSnapshot;
import com.example.rag.governance.service.ScopeFingerprint;
import com.example.rag.user.service.UserApplicationService;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 管理员创建固定执行身份的不可变数据集，启动时冻结权限与文档/索引快照。 */
@Service
public class EvaluationService {

    private final EvaluationRepository repository;
    private final PermissionScopeService permissions;
    private final UserApplicationService users;
    private final ScopeFingerprint scopes;
    private final RagConfigurationSnapshot configuration;
    private final EvaluationProperties config;
    private final ObjectMapper json;
    private final EvaluationMapper mapper;

    /** 统一注入管理权限、配置和持久化边界。 */
    public EvaluationService(
        EvaluationRepository repository,
        PermissionScopeService permissions,
        UserApplicationService users,
        ScopeFingerprint scopes,
        RagConfigurationSnapshot configuration,
        EvaluationProperties config,
        ObjectMapper json,
        EvaluationMapper mapper
    ) {
        this.repository = repository;
        this.permissions = permissions;
        this.users = users;
        this.scopes = scopes;
        this.configuration = configuration;
        this.config = config;
        this.json = json;
        this.mapper = mapper;
    }

    /** 普通用户即使手工请求管理接口也无法读取案例、答案或内部排序。 */
    public void authorize() {
        permissions.requireSystemAdmin(permissions.currentUser());
    }

    /** 校验全部正例范围和预期 Child；权限负例必须确实不能访问，不允许伪造预期拒绝。 */
    public long create(DatasetRequest request) {
        authorize();
        users.requireActive(request.evaluationUserId());
        if (request.cases().size() > config.maxCases()) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        for (CaseInput input : request.cases()) {
            String scope = fingerprint(request.evaluationUserId(), input);
            if (input.expectDenied() != EvaluationProtocol.DENIED.equals(scope)) {
                throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
            }
            if (input.expectDenied() && !input.expectedChunkIds().isEmpty()) {
                throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
            }
            for (Long chunkId : new HashSet<>(input.expectedChunkIds())) {
                List<Long> kbIds = mapper.childKnowledgeBases(chunkId);
                if (kbIds.isEmpty() || !input.knowledgeBaseIds().contains(kbIds.getFirst())) {
                    throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
                }
            }
        }
        return repository.create(request, permissions.currentUser().id());
    }

    /** 创建实验前固定数据集身份、案例、当前来源与不含密钥的配置快照。 */
    public long start(long datasetId, RunRequest request) {
        authorize();
        Map<String, Object> dataset = repository.dataset(datasetId);
        long userId = ((Number) dataset.get("evaluation_user_id")).longValue();
        List<Map<String, Object>> cases = repository.cases(datasetId);
        Map<String, String> fingerprints = new LinkedHashMap<>();
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Map<String, Object> row : cases) {
            CaseInput input = json.convertValue(row.get("snapshot"), CaseInput.class);
            String fingerprint = fingerprint(userId, input);
            if (input.expectDenied() != EvaluationProtocol.DENIED.equals(fingerprint)) {
                throw new BusinessException(ErrorCode.EVAL_409_SNAPSHOT_CHANGED);
            }
            fingerprints.put(row.get("id").toString(), fingerprint);
            if (!input.expectDenied()) {
                for (long chunkId : new HashSet<>(input.expectedChunkIds())) {
                    Integer current = mapper.currentChildCount(chunkId);
                    if (current != 1) {
                        throw new BusinessException(ErrorCode.EVAL_409_SNAPSHOT_CHANGED);
                    }
                }
                for (long kbId : new LinkedHashSet<>(input.knowledgeBaseIds())) {
                    documents.addAll(mapper.documents(kbId));
                }
            }
        }
        Map<String, Object> snapshot = Map.of(
            "configuration",
            configuration.values(),
            "configurationHash",
            configuration.digest(),
            "evaluationUserId",
            userId,
            "dataset",
            dataset,
            "cases",
            cases,
            "scopeFingerprints",
            fingerprints,
            "documents",
            documents,
            "metricK",
            config.metricK(),
            "judgeVersion",
            EvaluationProtocol.JUDGE_VERSION
        );
        return repository.enqueue(
            datasetId,
            request,
            snapshot,
            permissions.currentUser().id(),
            config.maxPendingRuns()
        );
    }

    /** 负例只允许确定的访问拒绝；基础设施故障和账号停用不得伪装为权限测试通过。 */
    public String fingerprint(long userId, CaseInput input) {
        try {
            RetrievalScope scope = permissions.resolveFor(userId, input.knowledgeBaseIds());
            return scopes.current(scope);
        } catch (BusinessException failure) {
            if (
                failure.errorCode() == ErrorCode.KB_403_FORBIDDEN ||
                failure.errorCode() == ErrorCode.KB_404_NOT_FOUND ||
                failure.errorCode() == ErrorCode.KB_409_DISABLED
            ) {
                return EvaluationProtocol.DENIED;
            }
            throw failure;
        }
    }

    /** 返回管理员可见的最近数据集列表。 */
    public List<Map<String, Object>> datasets() {
        authorize();
        return repository.datasets(EvaluationProtocol.LIST_LIMIT);
    }

    /** 返回不可变案例以便用户检查并创建修订数据集。 */
    public Map<String, Object> dataset(long id) {
        authorize();
        Map<String, Object> dataset = repository.dataset(id);
        dataset.put("cases", repository.cases(id));
        return dataset;
    }

    /** 返回同一数据集下的实验，便于并排比较同一题目集合。 */
    public List<Map<String, Object>> runs(long datasetId) {
        authorize();
        repository.dataset(datasetId);
        return repository.runs(datasetId, EvaluationProtocol.LIST_LIMIT);
    }

    /** 返回配置、来源、排序、回答和错误的完整可追溯实验详情。 */
    public Map<String, Object> run(long id) {
        authorize();
        return repository.run(id);
    }
}
