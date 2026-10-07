package com.example.rag.document.service;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentProtocol;
import com.example.rag.document.domain.upload.ValidatedUpload;
import com.example.rag.document.dto.DocumentResponse;
import com.example.rag.document.dto.UploadResponse;
import com.example.rag.document.mapper.DocumentMapper;
import com.example.rag.document.mapper.DocumentVersionMapper;
import com.example.rag.document.service.upload.UploadValidator;
import com.example.rag.document.storage.upload.DocumentStorage;
import com.example.rag.knowledgebase.domain.Permission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentVersionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionService.class);
    private final DocumentVersionMapper versions;
    private final DocumentMapper documents;
    private final PermissionScopeService permissions;
    private final UploadValidator validator;
    private final DocumentStorage storage;
    private final DocumentProperties config;
    private final TransactionTemplate transactions;

    /** 配置版本授权、原文件存储和事务提交依赖。 */
    public DocumentVersionService(
        DocumentVersionMapper versions,
        DocumentMapper documents,
        PermissionScopeService permissions,
        UploadValidator validator,
        DocumentStorage storage,
        DocumentProperties config,
        PlatformTransactionManager manager
    ) {
        this.versions = versions;
        this.documents = documents;
        this.permissions = permissions;
        this.validator = validator;
        this.storage = storage;
        this.config = config;
        transactions = new TransactionTemplate(manager);
    }

    /** 校验版本写权限并上传新原文件，提交失败时执行对象补偿。 */
    public UploadResponse upload(long documentId, int expectedCurrentVersion, MultipartFile multipart) {
        DocumentResponse document = require(documentId, Permission.WRITE);
        ValidatedUpload validatedUpload = validator.validate(multipart);
        String objectKey =
            "kb/" +
            document.knowledgeBaseId() +
            "/document/" +
            documentId +
            "/versions/" +
            UUID.randomUUID() +
            "." +
            validatedUpload.format().extension();
        Instant due = Instant.now().plus(config.cleanupGrace()).truncatedTo(ChronoUnit.MICROS);
        documents.registerUpload(objectKey, due);

        // 先保存对象，再在短事务内登记版本；提交失败时沿用原有补偿清理。
        boolean committed = false;
        try {
            storage.put(objectKey, validatedUpload);
            UploadResponse uploadResponse = transactions.execute(transaction ->
                createUploadedVersion(documentId, expectedCurrentVersion, validatedUpload, objectKey, due)
            );
            committed = true;
            return uploadResponse;
        } finally {
            if (!committed) {
                try {
                    if (!documents.objectReferenced(objectKey)) {
                        storage.remove(objectKey);
                    }
                    documents.clearUpload(objectKey);
                } catch (RuntimeException failure) {
                    log.warn("version upload cleanup deferred, documentId={}", documentId);
                }
            }
        }
    }

    /** 在锁定来源和上传补偿记录后创建新版本及索引任务。 */
    private UploadResponse createUploadedVersion(
        long documentId,
        int expectedCurrentVersion,
        ValidatedUpload validatedUpload,
        String objectKey,
        Instant due
    ) {
        if (documents.lockUpload(objectKey, due) == null) {
            throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
        }
        DocumentResponse currentDocument = lock(documentId, expectedCurrentVersion);
        if (
            !currentDocument.sha256().equals(validatedUpload.sha256()) &&
            documents.duplicate(currentDocument.knowledgeBaseId(), validatedUpload.sha256())
        ) {
            throw new BusinessException(ErrorCode.DOC_409_DUPLICATE);
        }
        int version = versions.nextVersion(documentId);
        versions.insert(
            documentId,
            version,
            validatedUpload,
            validatedUpload.format().mime(),
            validatedUpload.content().length,
            objectKey
        );
        versions.task(documentId, version, DocumentProtocol.INDEX);
        documents.clearUpload(objectKey);
        log.info("version build accepted, documentId={}, version={}", documentId, version);
        return new UploadResponse(documentId, version, DocumentProtocol.UPLOADED);
    }

    /** 复制当前版本的原文件信息并创建独立的重建版本。 */
    public UploadResponse reindex(long documentId, int expectedCurrentVersion) {
        require(documentId, Permission.WRITE);
        return transactions.execute(transaction -> {
            DocumentResponse document = lock(documentId, expectedCurrentVersion);
            int version = versions.nextVersion(documentId);
            versions.copy(documentId, version, document.currentVersion());
            versions.task(documentId, version, DocumentProtocol.REINDEX);
            log.info("reindex accepted, documentId={}, version={}", documentId, version);
            return new UploadResponse(documentId, version, DocumentProtocol.UPLOADED);
        });
    }

    /** 校验读权限后分页返回文档版本。 */
    public PageResponse<DocumentVersionMapper.Version> list(long documentId, PageRequest page) {
        require(documentId, Permission.READ);
        return new PageResponse<>(
            versions.versions(documentId, page.pageSize(), page.offset()),
            versions.count(documentId),
            page.page(),
            page.pageSize()
        );
    }

    /** 校验知识库当前读权限后读取文档清理任务，兼容已删除文档。 */
    public List<DocumentVersionMapper.Cleanup> cleanup(long documentId) {
        // 已删除文档的清理任务仍可查看，但必须具备知识库当前的 READ 权限。
        Long knowledgeBaseId = deletedKb(documentId);
        permissions.require(knowledgeBaseId, permissions.currentUser(), Permission.READ, false);
        return versions.cleanup(documentId);
    }

    /** 为已删除文档解析知识库，供清理记录权限校验使用。 */
    private Long deletedKb(long documentId) {
        Long knowledgeBaseId = versions.knowledgeBase(documentId);
        if (knowledgeBaseId == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        return knowledgeBaseId;
    }

    /** 锁定文档并校验预期版本及并发构建状态。 */
    private DocumentResponse lock(long documentId, int expectedCurrentVersion) {
        if (expectedCurrentVersion < 1) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        // 锁定顺序与版本构建保持一致，锁内再次检查权限和并发版本冲突。
        if (versions.lockKnowledgeBase(documentId) == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        if (versions.lock(documentId) == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        DocumentResponse document = require(documentId, Permission.WRITE);
        if (document.currentVersion() != expectedCurrentVersion || versions.building(documentId)) {
            throw new BusinessException(ErrorCode.DOC_409_VERSION_CONFLICT);
        }
        return document;
    }

    /** 校验文档存在和所属知识库的指定权限。 */
    private DocumentResponse require(long documentId, Permission permission) {
        DocumentResponse document = documents.find(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        permissions.require(document.knowledgeBaseId(), permissions.currentUser(), permission, false);
        return document;
    }
}
