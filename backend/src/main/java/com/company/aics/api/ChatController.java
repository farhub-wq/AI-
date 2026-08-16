package com.company.aics.api;

import com.company.aics.config.AuthenticatedUser;
import com.company.aics.rag.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话流式问答 API：以 SSE 推送 RAG 回答 token、引用与结束事件。
 * 鉴权后将请求委托给 {@link ChatService} 执行检索与生成。
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    /**
     * @param chatService RAG 流式问答服务
     */
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 启动一轮流式问答；鉴权后委托 {@link ChatService} 返回 {@link SseEmitter}。
     *
     * @param authentication 当前登录用户
     * @param request        会话、知识库、问题与历史轮数
     * @return SSE 发射器
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication, @Valid @RequestBody ApiModels.ChatRequest request) {
        AuthenticatedUser currentUser = CurrentUserSupport.require(authentication);
        return chatService.streamChat(
                currentUser.userId(),
                request.conversationId(),
                request.kbId(),
                request.question(),
                request.historyRounds()
        );
    }
}
