package com.company.aics.api;

import com.company.aics.application.ConversationService;
import com.company.aics.config.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理 API：创建会话、分页列表、会话详情（含消息）。
 * 所有操作均绑定当前登录用户，防止跨用户访问。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * @param conversationService 会话应用服务
     */
    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 创建新会话；标题为空时使用默认标题。
     */
    @PostMapping
    public ApiEnvelope<ApiModels.ConversationSummaryView> createConversation(
            Authentication authentication,
            @Valid @RequestBody ApiModels.CreateConversationRequest request
    ) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        var conversation = conversationService.createConversation(currentUser.userId(), request.title(), request.kbId());
        return ApiEnvelope.success(ApiMappers.toConversationSummaryView(conversation, List.of()));
    }

    /**
     * 分页列出当前用户会话，并附带末条消息预览。
     */
    @GetMapping
    public ApiEnvelope<List<ApiModels.ConversationSummaryView>> listConversations(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        List<ApiModels.ConversationSummaryView> data = conversationService
                .listUserConversations(currentUser.userId(), page, pageSize)
                .stream()
                .map(conversation -> ApiMappers.toConversationSummaryView(
                        conversation,
                        conversationService.listMessages(conversation.id())
                ))
                .toList();
        return ApiEnvelope.success(data);
    }

    /**
     * 获取指定会话详情（含全部消息）。
     */
    @GetMapping("/{conversationId}")
    public ApiEnvelope<ApiModels.ConversationDetailView> getConversationDetail(
            Authentication authentication,
            @PathVariable Long conversationId
    ) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return ApiEnvelope.success(ApiMappers.toConversationDetailView(
                conversationService.getConversationDetail(currentUser.userId(), conversationId)
        ));
    }
}
