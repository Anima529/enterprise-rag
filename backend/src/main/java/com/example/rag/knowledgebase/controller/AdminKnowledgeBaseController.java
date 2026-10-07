package com.example.rag.knowledgebase.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.common.security.AccountProtocol;
import com.example.rag.knowledgebase.dto.AclEntry;
import com.example.rag.knowledgebase.dto.KnowledgeBaseResponse;
import com.example.rag.knowledgebase.dto.ReplaceAclRequest;
import com.example.rag.knowledgebase.dto.UpdateKnowledgeBaseRequest;
import com.example.rag.knowledgebase.service.KnowledgeBaseApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/knowledge-bases")
@SaCheckRole(AccountProtocol.SYSTEM_ADMIN)
public class AdminKnowledgeBaseController {

    private final KnowledgeBaseApplicationService service;

    /** 配置请求入口所依赖的应用服务。 */
    public AdminKnowledgeBaseController(KnowledgeBaseApplicationService service) {
        this.service = service;
    }

    /** 分页查询管理范围内的知识库。 */
    @GetMapping
    public ApiResponse<PageResponse<KnowledgeBaseResponse>> list(@Valid @ModelAttribute PageRequest page) {
        PageResponse<KnowledgeBaseResponse> pageResponse = service.list(page, true);
        return ApiResponse.success(pageResponse);
    }

    /** 查询管理范围内的知识库详情。 */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> detail(@PathVariable long id) {
        KnowledgeBaseResponse knowledgeBase = service.detail(id, true);
        return ApiResponse.success(knowledgeBase);
    }

    /** 更新管理范围内的知识库设置。 */
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseResponse> update(
        @PathVariable long id,
        @Valid @RequestBody UpdateKnowledgeBaseRequest request
    ) {
        KnowledgeBaseResponse knowledgeBase = service.update(id, request, true);
        return ApiResponse.success(knowledgeBase);
    }

    /** 删除管理范围内的知识库。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id, true);
        return ApiResponse.success(null);
    }

    /** 查询管理范围内的知识库 ACL。 */
    @GetMapping("/{id}/acl")
    public ApiResponse<List<AclEntry>> acl(@PathVariable long id) {
        List<AclEntry> entries = service.acl(id, true);
        return ApiResponse.success(entries);
    }

    /** 替换管理范围内的知识库 ACL 授权。 */
    @PutMapping("/{id}/acl")
    public ApiResponse<List<AclEntry>> replaceAcl(
        @PathVariable long id,
        @Valid @RequestBody ReplaceAclRequest request
    ) {
        List<AclEntry> entries = service.replaceAcl(id, request, true);
        return ApiResponse.success(entries);
    }
}
