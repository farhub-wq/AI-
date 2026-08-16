package com.company.aics.api;

import com.company.aics.application.FeedbackService;
import com.company.aics.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息反馈 API：对助手回答提交点赞/点踩及原因。
 * 仅允许消息所属用户对 ASSISTANT 消息反馈。
 */
@RestController
@RequestMapping("/api/v1/messages")
public class FeedbackController {

    private final FeedbackService feedbackService;

    /**
     * @param feedbackService 反馈应用服务
     */
    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 提交或覆盖某条助手消息的反馈。
     *
     * @param authentication 当前登录用户
     * @param messageId     助手消息 ID
     * @param request       评分、原因码与可选评论
     */
    @PostMapping("/{messageId}/feedback")
    public ApiEnvelope<ApiModels.FeedbackResponse> submitFeedback(
            Authentication authentication,
            @PathVariable Long messageId,
            @Valid @RequestBody ApiModels.FeedbackRequest request
    ) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return ApiEnvelope.success(ApiMappers.toFeedbackResponse(
                feedbackService.submitFeedback(
                        currentUser.userId(),
                        messageId,
                        request.rating(),
                        request.reasonCode(),
                        request.comment()
                )
        ));
    }
}
