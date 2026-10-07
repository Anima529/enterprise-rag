package com.example.rag.governance.service;

import com.example.rag.common.security.RetrievalScope;
import com.example.rag.governance.mapper.GovernanceMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 实时读取授权主体、ACL 和当前文档版本，缓存与评估使用同一失效边界。 */
@Service
public class ScopeFingerprint {

    private final GovernanceMapper mapper;

    /** 只读取已签发范围的状态，不提供越权检索入口。 */
    public ScopeFingerprint(GovernanceMapper mapper) {
        this.mapper = mapper;
    }

    /** 同一身份及知识库集合内，账号、ACL、版本或删除变化都会生成不同指纹。 */
    public String current(RetrievalScope scope) {
        StringBuilder snapshot = new StringBuilder();
        snapshot.append(mapper.userSnapshot(scope.userId()));
        List<Long> ids = scope
            .knowledgeBases()
            .stream()
            .map(RetrievalScope.KnowledgeBaseDepartment::knowledgeBaseId)
            .sorted()
            .toList();
        for (long id : ids) {
            snapshot.append(mapper.knowledgeBaseSnapshot(id));
            snapshot.append(mapper.aclSnapshot(id));
            snapshot.append(mapper.documentSnapshot(id));
        }
        return digest(snapshot.toString());
    }

    /** 使用稳定 SHA-256 隔离配置和范围，不在 Key 中暴露文档正文或用户问题。 */
    public static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
