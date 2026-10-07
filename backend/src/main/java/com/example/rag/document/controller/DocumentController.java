package com.example.rag.document.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.document.domain.chunk.ChunkType;
import com.example.rag.document.dto.DocumentResponse;
import com.example.rag.document.dto.DocumentTaskResponse;
import com.example.rag.document.dto.DownloadResponse;
import com.example.rag.document.dto.UploadPolicyResponse;
import com.example.rag.document.dto.UploadResponse;
import com.example.rag.document.dto.chunk.ChunkPreviewResponse;
import com.example.rag.document.dto.embedding.EmbeddingPreviewResponse;
import com.example.rag.document.dto.index.IndexStepResponse;
import com.example.rag.document.dto.parser.ParsePreviewResponse;
import com.example.rag.document.service.DocumentApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentApplicationService service;

    /** 配置请求入口所依赖的应用服务。 */
    public DocumentController(DocumentApplicationService service) {
        this.service = service;
    }

    /** 上传知识库文档并创建处理任务。 */
    @PostMapping(value = "/knowledge-bases/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<UploadResponse> upload(@PathVariable long kbId, @RequestPart("file") MultipartFile file) {
        UploadResponse uploadResponse = service.upload(kbId, file);
        return ApiResponse.success(uploadResponse);
    }

    /** 分页查询知识库文档。 */
    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ApiResponse<PageResponse<DocumentResponse>> list(
        @PathVariable long kbId,
        @Valid @ModelAttribute PageRequest page
    ) {
        PageResponse<DocumentResponse> pageResponse = service.list(kbId, page);
        return ApiResponse.success(pageResponse);
    }

    /** 查询文档详情。 */
    @GetMapping("/documents/{id}")
    public ApiResponse<DocumentResponse> detail(@PathVariable long id) {
        DocumentResponse document = service.detail(id);
        return ApiResponse.success(document);
    }

    /** 查询文档上传限制。 */
    @GetMapping("/knowledge-bases/{kbId}/documents/upload-policy")
    public ApiResponse<UploadPolicyResponse> uploadPolicy(@PathVariable long kbId) {
        UploadPolicyResponse uploadPolicy = service.uploadPolicy(kbId);
        return ApiResponse.success(uploadPolicy);
    }

    /** 分页查询文档处理任务。 */
    @GetMapping("/documents/{id}/tasks")
    public ApiResponse<PageResponse<DocumentTaskResponse>> tasks(
        @PathVariable long id,
        @Valid @ModelAttribute PageRequest page
    ) {
        PageResponse<DocumentTaskResponse> pageResponse = service.tasks(id, page);
        return ApiResponse.success(pageResponse);
    }

    /** 获取指定版本原文件的下载地址。 */
    @GetMapping("/documents/{id}/download")
    public ApiResponse<DownloadResponse> download(
        @PathVariable long id,
        @RequestParam(required = false) Integer version
    ) {
        DownloadResponse download = service.download(id, version);
        return ApiResponse.success(download);
    }

    /** 分页预览指定版本的解析正文。 */
    @GetMapping("/documents/{id}/parsed-blocks")
    public ApiResponse<ParsePreviewResponse> parsedBlocks(
        @PathVariable long id,
        @RequestParam(required = false) Integer version,
        @Valid @ModelAttribute PageRequest page
    ) {
        ParsePreviewResponse parsePreview = service.parsedBlocks(id, version, page);
        return ApiResponse.success(parsePreview);
    }

    /** 重新排队可重试的文档任务。 */
    @PostMapping("/documents/{id}/tasks/{taskId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> retry(@PathVariable long id, @PathVariable long taskId) {
        service.retryTask(id, taskId);
        return ApiResponse.success(null);
    }

    /** 分页预览指定版本的父子分块。 */
    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<ChunkPreviewResponse> chunks(
        @PathVariable long id,
        @RequestParam(required = false) Integer version,
        @RequestParam(required = false) ChunkType chunkType,
        @Valid @ModelAttribute PageRequest page
    ) {
        ChunkPreviewResponse chunkPreview = service.chunks(id, version, chunkType, page);
        return ApiResponse.success(chunkPreview);
    }

    /** 查询指定版本的向量索引摘要。 */
    @GetMapping("/documents/{id}/embedding")
    public ApiResponse<EmbeddingPreviewResponse> embedding(
        @PathVariable long id,
        @RequestParam(required = false) Integer version
    ) {
        EmbeddingPreviewResponse embeddingPreview = service.embedding(id, version);
        return ApiResponse.success(embeddingPreview);
    }

    /** 分页查询文档任务的执行步骤。 */
    @GetMapping("/documents/{id}/tasks/{taskId}/steps")
    public ApiResponse<PageResponse<IndexStepResponse>> steps(
        @PathVariable long id,
        @PathVariable long taskId,
        @Valid @ModelAttribute PageRequest page
    ) {
        PageResponse<IndexStepResponse> pageResponse = service.taskSteps(id, taskId, page);
        return ApiResponse.success(pageResponse);
    }

    /** 删除文档并登记后续清理任务。 */
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.success(null);
    }
}
