package com.example.rag.document.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.document.dto.UploadResponse;
import com.example.rag.document.mapper.DocumentVersionMapper;
import com.example.rag.document.service.DocumentVersionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents/{id}")
public class DocumentVersionController {

    public record ReindexRequest(@Min(1) int expectedCurrentVersion) {}

    private final DocumentVersionService service;

    /** 配置请求入口所依赖的应用服务。 */
    public DocumentVersionController(DocumentVersionService service) {
        this.service = service;
    }

    /** 上传文档新版本并排队构建。 */
    @PostMapping(value = "/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<UploadResponse> upload(
        @PathVariable long id,
        @RequestPart("file") MultipartFile file,
        @RequestParam int expectedCurrentVersion
    ) {
        UploadResponse uploadResponse = service.upload(id, expectedCurrentVersion, file);
        return ApiResponse.success(uploadResponse);
    }

    /** 为当前文档创建重建索引版本。 */
    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<UploadResponse> reindex(@PathVariable long id, @Valid @RequestBody ReindexRequest request) {
        UploadResponse uploadResponse = service.reindex(id, request.expectedCurrentVersion());
        return ApiResponse.success(uploadResponse);
    }

    /** 分页查询文档版本。 */
    @GetMapping("/versions")
    public ApiResponse<PageResponse<DocumentVersionMapper.Version>> versions(
        @PathVariable long id,
        @Valid @ModelAttribute PageRequest page
    ) {
        PageResponse<DocumentVersionMapper.Version> pageResponse = service.list(id, page);
        return ApiResponse.success(pageResponse);
    }

    /** 查询文档版本的清理任务。 */
    @GetMapping("/cleanup-tasks")
    public ApiResponse<List<DocumentVersionMapper.Cleanup>> cleanup(@PathVariable long id) {
        List<DocumentVersionMapper.Cleanup> entries = service.cleanup(id);
        return ApiResponse.success(entries);
    }
}
