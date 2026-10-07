package com.example.rag.conversation.controller;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.conversation.service.CitationSourceService;
import com.example.rag.document.dto.DownloadResponse;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/conversations/{conversationId}/messages/{messageId}/citations")
public class CitationSourceController {

    private final CitationSourceService service;

    /** 配置授权引用原文读取入口。 */
    public CitationSourceController(CitationSourceService service) {
        this.service = service;
    }

    /** 按会话、消息和白名单编号读取原文，预览重新鉴权并固定历史文档版本。 */
    @GetMapping("/{citationId}/source")
    public ApiResponse<CitationSourceService.Source> source(
        @Positive @PathVariable long conversationId,
        @Positive @PathVariable long messageId,
        @Pattern(regexp = "C[1-9][0-9]*") @PathVariable String citationId
    ) {
        CitationSourceService.Source source = service.source(conversationId, messageId, citationId);
        return ApiResponse.success(source);
    }

    /** 限定引用中的 PDF 历史版本，签发能被浏览器按页打开的短期地址。 */
    @GetMapping("/{citationId}/pdf")
    public ApiResponse<DownloadResponse> pdf(
        @Positive @PathVariable long conversationId,
        @Positive @PathVariable long messageId,
        @Pattern(regexp = "C[1-9][0-9]*") @PathVariable String citationId
    ) {
        DownloadResponse preview = service.pdf(conversationId, messageId, citationId);
        return ApiResponse.success(preview);
    }
}
