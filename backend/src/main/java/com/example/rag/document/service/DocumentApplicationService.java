package com.example.rag.document.service;

import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.exception.BusinessException;
import com.example.rag.common.exception.ErrorCode;
import com.example.rag.common.security.PermissionScopeService;
import com.example.rag.document.config.DocumentProperties;
import com.example.rag.document.domain.DocumentFormat;
import com.example.rag.document.domain.DocumentProtocol;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.domain.index.IndexTaskLease;
import com.example.rag.document.domain.upload.ValidatedUpload;
import com.example.rag.document.dto.DocumentResponse;
import com.example.rag.document.dto.DocumentTaskResponse;
import com.example.rag.document.dto.DownloadResponse;
import com.example.rag.document.dto.UploadPolicyResponse;
import com.example.rag.document.dto.UploadResponse;
import com.example.rag.document.dto.chunk.ChunkPreviewResponse;
import com.example.rag.document.dto.embedding.EmbeddingPreviewResponse;
import com.example.rag.document.dto.index.IndexStepResponse;
import com.example.rag.document.dto.parser.ParsePreviewResponse;
import com.example.rag.document.mapper.DocumentMapper;
import com.example.rag.document.mapper.index.IndexTaskMapper;
import com.example.rag.document.repository.chunk.DocumentChunkRepository;
import com.example.rag.document.repository.embedding.DocumentEmbeddingRepository;
import com.example.rag.document.repository.index.IndexTaskStore;
import com.example.rag.document.repository.parser.ParsedDocumentRepository;
import com.example.rag.document.service.upload.UploadValidator;
import com.example.rag.document.storage.upload.DocumentStorage;
import com.example.rag.knowledgebase.domain.Permission;
import com.example.rag.knowledgebase.mapper.KnowledgeBaseMapper;
import com.example.rag.user.domain.UserAccount;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentApplicationService.class);
    private final DocumentMapper mapper;
    private final KnowledgeBaseMapper knowledgeBases;
    private final PermissionScopeService permissions;
    private final UploadValidator validator;
    private final DocumentStorage storage;
    private final DocumentProperties properties;
    private final TransactionTemplate transactions;
    private final IndexTaskStore indexTasks;
    private final IndexTaskMapper indexTaskMapper;
    private final ParsedDocumentRepository parsedDocuments;
    private final DocumentChunkRepository chunks;
    private final DocumentEmbeddingRepository embeddings;

    /** 配置文档权限、存储、索引及预览依赖，建立上传事务边界。 */
    public DocumentApplicationService(
        DocumentMapper mapper,
        KnowledgeBaseMapper knowledgeBases,
        PermissionScopeService permissions,
        UploadValidator validator,
        DocumentStorage storage,
        DocumentProperties properties,
        PlatformTransactionManager transactionManager,
        IndexTaskStore indexTasks,
        IndexTaskMapper indexTaskMapper,
        ParsedDocumentRepository parsedDocuments,
        DocumentChunkRepository chunks,
        DocumentEmbeddingRepository embeddings
    ) {
        this.mapper = mapper;
        this.knowledgeBases = knowledgeBases;
        this.permissions = permissions;
        this.validator = validator;
        this.storage = storage;
        this.properties = properties;
        transactions = new TransactionTemplate(transactionManager);
        this.indexTasks = indexTasks;
        this.indexTaskMapper = indexTaskMapper;
        this.parsedDocuments = parsedDocuments;
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    /** 校验上传与写权限，保存原文件并在事务中创建文档、版本和索引任务。 */
    public UploadResponse upload(long kbId, MultipartFile multipart) {
        permissions.require(kbId, permissions.currentUser(), Permission.WRITE, false);
        ValidatedUpload file = validator.validate(multipart);
        duplicate(kbId, file.sha256());
        long id = mapper.nextId();
        String key = "kb/" + kbId + "/document/" + id + "/v1/" + UUID.randomUUID() + "." + file.format().extension();
        Instant cleanupDue = Instant.now().plus(properties.cleanupGrace()).truncatedTo(ChronoUnit.MICROS);
        mapper.registerUpload(key, cleanupDue);
        boolean committed = false;
        try {
            storage.put(key, file);
            // 事务内只执行数据库操作，并重新校验当前 ACL。
            transactions.executeWithoutResult(transaction -> {
                // 清理线程变更租约后，本次上传失效；锁定原始上传意图，
                // 使 SKIP LOCKED 清理不会删除正在等待数据库提交的对象。
                if (mapper.lockUpload(key, cleanupDue) == null) {
                    throw new BusinessException(ErrorCode.STORAGE_503_UNAVAILABLE);
                }
                lockKb(kbId);
                UserAccount actor = permissions.currentUser();
                permissions.require(kbId, actor, Permission.WRITE, false);
                duplicate(kbId, file.sha256());
                mapper.insert(id, kbId, file, file.format().mime(), file.content().length, key, actor.id());
                mapper.insertVersion(id);
                mapper.insertTask(id);
                mapper.clearUpload(key);
                log.info("document upload accepted, documentId={}, kbId={}, actorId={}", id, kbId, actor.id());
            });
            committed = true;
            return new UploadResponse(id, 1, DocumentProtocol.UPLOADED);
        } finally {
            if (!committed) {
                compensate(id, key);
            }
        }
    }

    /** 校验知识库读权限后分页查询文档。 */
    public PageResponse<DocumentResponse> list(long kbId, PageRequest page) {
        permissions.require(kbId, permissions.currentUser(), Permission.READ, false);
        return new PageResponse<>(
            mapper.list(kbId, page.pageSize(), page.offset()),
            mapper.count(kbId),
            page.page(),
            page.pageSize()
        );
    }

    /** 校验文档读权限并返回最新元数据。 */
    public DocumentResponse detail(long id) {
        return require(id, Permission.READ);
    }

    /** 校验读权限后查询指定或当前版本的解析预览。 */
    public ParsePreviewResponse parsedBlocks(long id, Integer version, PageRequest page) {
        DocumentResponse document = require(id, Permission.READ);
        int requested = version == null ? document.currentVersion() : version;
        if (requested < 1) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        return parsedDocuments.preview(id, requested, page);
    }

    /** 校验写权限并返回服务器允许的格式及资源限制。 */
    public UploadPolicyResponse uploadPolicy(long kbId) {
        permissions.require(kbId, permissions.currentUser(), Permission.READ, false);
        return new UploadPolicyResponse(
            Arrays.stream(DocumentFormat.values())
                .map(f -> "." + f.extension())
                .toList(),
            properties.maxBytes(),
            properties.maxTextCharacters()
        );
    }

    /** 校验读权限后按版本和 Chunk 类型分页查询片段。 */
    public ChunkPreviewResponse chunks(long id, Integer version, ChunkType type, PageRequest page) {
        DocumentResponse document = require(id, Permission.READ);
        int requested = version == null ? document.currentVersion() : version;
        if (requested < 1) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        return chunks.preview(id, requested, type, page);
    }

    /** 校验读权限后分页读取文档索引任务。 */
    public PageResponse<DocumentTaskResponse> tasks(long id, PageRequest page) {
        require(id, Permission.READ);
        return new PageResponse<>(
            mapper.tasks(id, page.pageSize(), page.offset()),
            mapper.countTasks(id),
            page.page(),
            page.pageSize()
        );
    }

    /** 校验读权限及版本后签发限时原文件下载地址。 */
    public DownloadResponse download(long id, Integer version) {
        DocumentResponse document = require(id, Permission.READ);
        int requestedVersion = version == null ? document.currentVersion() : version;
        if (requestedVersion < 1) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        String key = mapper.objectKey(id, requestedVersion);
        if (key == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        Instant expiresAt = Instant.now().plusSeconds(properties.downloadTtlSeconds());
        String url = storage.download(key, mapper.versionName(id, requestedVersion), properties.downloadTtlSeconds());
        log.info(
            "document download authorized, documentId={}, version={}, actorId={}",
            id,
            requestedVersion,
            permissions.currentUser().id()
        );
        return new DownloadResponse(url, expiresAt);
    }

    /** 再次校验 READ 和历史版本格式，只为 PDF 签发短期 inline 预览地址。 */
    public DownloadResponse previewPdf(long id, int version) {
        require(id, Permission.READ);
        if (mapper.versionFormat(id, version) != DocumentFormat.PDF) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        String key = mapper.objectKey(id, version);
        if (key == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        String url = storage.previewPdf(key, mapper.versionName(id, version), properties.downloadTtlSeconds());
        return new DownloadResponse(url, Instant.now().plusSeconds(properties.downloadTtlSeconds()));
    }

    /** 校验读权限后读取指定版本的 Embedding 汇总。 */
    public EmbeddingPreviewResponse embedding(long id, Integer version) {
        DocumentResponse document = require(id, Permission.READ);
        int requestedVersion = version == null ? document.currentVersion() : version;
        if (requestedVersion < 1) {
            throw new BusinessException(ErrorCode.COMMON_400_VALIDATION);
        }
        if (mapper.objectKey(id, requestedVersion) == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        return embeddings.preview(id, requestedVersion);
    }

    /** 校验最新写权限及任务归属后申请重试失败任务。 */
    public void retryTask(long id, long taskId) {
        DocumentResponse document = require(id, Permission.WRITE);
        transactions.executeWithoutResult(transaction -> {
            lockKb(document.knowledgeBaseId());
            require(id, Permission.WRITE);
            indexTasks.retry(id, taskId);
            log.info(
                "index task retry requested, taskId={}, documentId={}, actorId={}",
                taskId,
                id,
                permissions.currentUser().id()
            );
        });
    }

    /** 校验文档与任务归属后分页读取索引阶段记录。 */
    public PageResponse<IndexStepResponse> taskSteps(long id, long taskId, PageRequest page) {
        require(id, Permission.READ);
        IndexTaskLease task = indexTaskMapper.find(taskId);
        if (task == null || task.documentId() != id) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        return new PageResponse<>(
            indexTaskMapper.steps(taskId, page.pageSize(), page.offset()),
            indexTaskMapper.countSteps(taskId),
            page.page(),
            page.pageSize()
        );
    }

    /** 校验写权限后软删除文档并安排异步清理。 */
    public void delete(long id) {
        DocumentResponse document = require(id, Permission.WRITE);
        transactions.executeWithoutResult(transaction -> {
            lockKb(document.knowledgeBaseId());
            require(id, Permission.WRITE);
            mapper.softDelete(id);
            mapper.scheduleDeletion(id, Instant.now().plus(properties.cleanupGrace()));
            log.info("document deleted, documentId={}, actorId={}", id, permissions.currentUser().id());
        });
    }

    /** 从文档定位知识库并检查所需权限，拒绝不存在或已删除的来源。 */
    private DocumentResponse require(long id, Permission permission) {
        UserAccount actor = permissions.currentUser();
        DocumentResponse document = mapper.find(id);
        if (document == null) {
            throw new BusinessException(ErrorCode.DOC_404_NOT_FOUND);
        }
        permissions.require(document.knowledgeBaseId(), actor, permission, false);
        return document;
    }

    /** 锁定知识库有效记录，使上传提交与权限调整保持顺序。 */
    private void lockKb(long id) {
        if (knowledgeBases.lock(id) == null) {
            throw new BusinessException(ErrorCode.KB_404_NOT_FOUND);
        }
    }

    /** 拒绝同一知识库中的有效同内容文档。 */
    private void duplicate(long kbId, String sha) {
        if (mapper.duplicate(kbId, sha)) {
            throw new BusinessException(ErrorCode.DOC_409_DUPLICATE);
        }
    }

    /** 创建文档失败后尝试删除原文件，清理失败时保留持久化补偿记录。 */
    private void compensate(long id, String key) {
        try {
            // 即使提交确认丢失，也不能删除已经提交的对象。
            if (!mapper.objectReferenced(key)) {
                storage.remove(key);
            }
            mapper.clearUpload(key);
        } catch (RuntimeException exception) {
            log.warn(
                "upload compensation deferred, documentId={}, exceptionType={}",
                id,
                exception.getClass().getSimpleName()
            );
        }
    }
}
