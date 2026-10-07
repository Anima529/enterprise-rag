package com.example.rag.conversation.controller;

import static com.example.rag.conversation.dto.ConversationModels.*;

import com.example.rag.common.api.ApiResponse;
import com.example.rag.common.api.PageRequest;
import com.example.rag.common.api.PageResponse;
import com.example.rag.conversation.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService service;

    /** 配置请求入口所依赖的应用服务。 */
    public ConversationController(ConversationService service) {
        this.service = service;
    }

    /** 创建当前用户的会话。 */
    @PostMapping
    public ApiResponse<Conversation> create(@Valid @RequestBody TitleRequest request) {
        Conversation conversation = service.create(request.title());
        return ApiResponse.success(conversation);
    }

    /** 分页查询当前用户的会话。 */
    @GetMapping
    public ApiResponse<PageResponse<Conversation>> list(@Valid @ModelAttribute PageRequest page) {
        PageResponse<Conversation> pageResponse = service.list(page);
        return ApiResponse.success(pageResponse);
    }

    /** 查询会话详情。 */
    @GetMapping("/{id}")
    public ApiResponse<Conversation> detail(@PathVariable long id) {
        Conversation conversation = service.detail(id);
        return ApiResponse.success(conversation);
    }

    /** 修改会话标题。 */
    @PutMapping("/{id}")
    public ApiResponse<Conversation> rename(@PathVariable long id, @Valid @RequestBody TitleRequest request) {
        Conversation conversation = service.rename(id, request.title());
        return ApiResponse.success(conversation);
    }

    /** 删除会话及其消息。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.success(null);
    }

    /** 分页查询会话消息及引用快照。 */
    @GetMapping("/{id}/messages")
    public ApiResponse<PageResponse<Message>> messages(@PathVariable long id, @Valid @ModelAttribute PageRequest page) {
        PageResponse<Message> pageResponse = service.messages(id, page);
        return ApiResponse.success(pageResponse);
    }
}
