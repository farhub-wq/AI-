package com.company.aics.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时打印 LLM 基础 URL/模型及 API Key 是否已配置，便于排查联调环境。
 * 不输出密钥明文，仅记录是否已替换占位值。
 */
@Component
public class AiConfigStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(AiConfigStartupLogger.class);

    private final AiProperties aiProperties;

    /**
     * @param aiProperties AI 相关配置属性
     */
    public AiConfigStartupLogger(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    /**
     * 容器初始化后输出关键 LLM 配置摘要。
     */
    @PostConstruct
    void logConfig() {
        // 仅判断 Key 是否已配置，避免日志泄露明文密钥
        log.info(
                "Loaded AI config: llmBaseUrl={}, llmChatModel={}, llmApiKeyPresent={}",
                aiProperties.getLlmBaseUrl(),
                aiProperties.getLlmChatModel(),
                StringUtils.hasText(aiProperties.getLlmApiKey()) && !"replace-me".equals(aiProperties.getLlmApiKey())
        );
    }
}
