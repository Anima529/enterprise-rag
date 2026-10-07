package com.example.rag.knowledgebase.service;

import com.example.rag.common.api.ApiContractLimits;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.AccountProtocol;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.knowledgebase.domain.Permission;
import com.example.rag.knowledgebase.domain.SubjectType;
import com.example.rag.knowledgebase.dto.AclEntry;
import com.example.rag.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.example.rag.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.rag.knowledgebase.dto.ReplaceAclRequest;
import com.example.rag.knowledgebase.dto.UpdateKnowledgeBaseRequest;
import com.example.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.rag.rag.chat.config.ChatProperties;
import com.example.rag.rag.model.config.EmbeddingProperties;
import com.example.rag.user.domain.UserAccount;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseApplicationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseApplicationService.class);
    private final KnowledgeBaseMapper mapper;
    private final PermissionScopeService permissions;
    private final EmbeddingProperties embedding;
    private final ChatProperties chat;

    /** 配置知识库授权及默认模型依赖。 */
    public KnowledgeBaseApplicationService(
        KnowledgeBaseMapper mapper,
        PermissionScopeService permissions,
        EmbeddingProperties embedding,
        ChatProperties chat
    ) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.embedding = embedding;
        this.chat = chat;
    }

    /** 按访问或管理入口分页查询当前用户可见的知识库。 */
    public PageResponse<KnowledgeBaseResponse> list(PageRequest page, boolean management) {
        UserAccount actor = permissions.currentUser();
        if (management) {
            permissions.requireSystemAdmin(actor);
        }
        return new PageResponse<>(
            mapper.list(actor, management, page.pageSize(), page.offset()),
            mapper.count(actor, management),
            page.page(),
            page.pageSize()
        );
    }

    /** 校验入口权限并查询知识库详情。 */
    public KnowledgeBaseResponse detail(long id, boolean management) {
        return permissions.require(id, permissions.currentUser(), Permission.READ, management);
    }

    /** 校验管理员身份与部门后创建默认私有知识库。 */
    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        UserAccount actor = permissions.currentUser();
        long ownerId = request.ownerUserId() == null ? actor.id() : request.ownerUserId();
        if (request.departmentId().longValue() != actor.departmentId() || ownerId != actor.id()) {
            permissions.requireSystemAdmin(actor);
        }
        if (!mapper.activeDepartment(request.departmentId()) || !mapper.activeUser(ownerId)) {
            invalid();
        }
        long id = mapper.insert(
            request.name().strip(),
            description(request.description()),
            request.departmentId(),
            ownerId,
            embedding.model(),
            embedding.dimensions(),
            chat.model()
        );
        log.info("knowledge base created, kbId={}, actorId={}, ownerId={}", id, actor.id(), ownerId);
        return mapper.find(id, actor);
    }

    /** 锁定知识库并校验管理权限后更新可编辑属性。 */
    @Transactional
    public KnowledgeBaseResponse update(long id, UpdateKnowledgeBaseRequest request, boolean management) {
        lock(id);
        UserAccount actor = permissions.currentUser();
        KnowledgeBaseResponse kb = permissions.require(id, actor, Permission.WRITE, management);
        if (request.status() != kb.status()) {
            permissions.require(id, actor, Permission.ADMIN, management);
        }
        mapper.update(id, request.name().strip(), description(request.description()), request.status());
        log.info("knowledge base updated, kbId={}, actorId={}, status={}", id, actor.id(), request.status());
        return mapper.find(id, actor);
    }

    /** 校验管理权限后软删除知识库。 */
    @Transactional
    public void delete(long id, boolean management) {
        lock(id);
        UserAccount actor = permissions.currentUser();
        permissions.require(id, actor, Permission.ADMIN, management);
        mapper.softDelete(id);
        log.info("knowledge base deleted, kbId={}, actorId={}", id, actor.id());
    }

    /** 校验知识库管理权限并读取 ACL。 */
    public List<AclEntry> acl(long id, boolean management) {
        permissions.require(id, permissions.currentUser(), Permission.ADMIN, management);
        return mapper.acl(id);
    }

    /** 校验完整授权集合后在事务内替换 ACL，支持空集合撤销授权。 */
    @Transactional
    public List<AclEntry> replaceAcl(long id, ReplaceAclRequest request, boolean management) {
        lock(id);
        UserAccount actor = permissions.currentUser();
        permissions.require(id, actor, Permission.ADMIN, management);
        validateSubjects(request.entries());
        mapper.deleteAcl(id);
        for (AclEntry entry : request.entries()) {
            mapper.insertAcl(id, entry);
        }
        log.info(
            "knowledge base ACL replaced, kbId={}, actorId={}, entryCount={}",
            id,
            actor.id(),
            request.entries().size()
        );
        return mapper.acl(id);
    }

    /** 校验用户、部门及角色主体有效且不重复。 */
    private void validateSubjects(List<AclEntry> entries) {
        var unique = new HashSet<String>();
        for (AclEntry entry : entries) {
            if (!unique.add(entry.subjectType() + ":" + entry.subjectId())) {
                invalid();
            }
            if (entry.subjectType() == SubjectType.ROLE) {
                if (!List.of(AccountProtocol.USER, AccountProtocol.SYSTEM_ADMIN).contains(entry.subjectId())) {
                    invalid();
                }
            } else {
                if (!entry.subjectId().matches("[1-9][0-9]{0,15}")) {
                    invalid();
                }
                long subject = Long.parseLong(entry.subjectId());
                if (subject > ApiContractLimits.MAX_SAFE_ID) {
                    invalid();
                }
                boolean active =
                    entry.subjectType() == SubjectType.USER
                        ? mapper.activeUser(subject)
                        : mapper.activeDepartment(subject);
                if (!active) {
                    invalid();
                }
            }
        }
    }

    /** 锁定知识库记录，使权限复查与写入保持顺序。 */
    private void lock(long id) {
        if (mapper.lock(id) == null) {
            throw new BusinessException(ErrorCode.KB_404_NOT_FOUND);
        }
        // 取得共享知识库行锁后再校验权限，使并发撤权与写操作按顺序执行。
    }

    /** 将可选说明规范化为数据库存储值。 */
    private String description(String value) {
        return value == null ? "" : value.strip();
    }

    /** 构造知识库参数校验失败的统一错误。 */
    private void invalid() {
        throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
    }
}
