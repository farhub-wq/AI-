package com.company.aics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 相关配置属性（{@code ai.*}）：LLM/Embedding 端点、Faiss 本地索引目录、RAG 检索阈值与日提问上限等。
 * 由 Spring Boot 从配置文件绑定，供 RAG、向量索引与限流逻辑读取。
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** LLM OpenAI 兼容接口基础 URL。 */
    private String llmBaseUrl;
    /** LLM API Key。 */
    private String llmApiKey;
    /** 对话所用模型名。 */
    private String llmChatModel;
    /** Embedding 服务基础 URL。 */
    private String embeddingBaseUrl;
    /** Embedding API Key。 */
    private String embeddingApiKey;
    /** Embedding 模型名。 */
    private String embeddingModel;
    /** Faiss 本地文件索引目录（相对后端工作目录）。 */
    private String faissIndexDir = "data/faiss-index";
    /** RAG 检索返回的最大条数。 */
    private Integer ragTopK = 12;
    /** RAG 相似度分数阈值，低于此值的命中丢弃。 */
    private Double ragScoreThreshold = 0.35;
    /** 拼入 Prompt 的检索上下文最大字符数。 */
    private Integer ragMaxContextChars = 6000;
    /** 单用户每日提问上限。 */
    private Integer dailyQuestionLimit = 100;
    /** 默认客服支持知识库 ID。 */
    private Long defaultSupportKbId = 1L;
    /** 默认技术文档知识库 ID。 */
    private Long defaultTechnicalKbId = 2L;
    /** LLM 最大尝试次数（含首次；超时/限流/5xx 可重试）。 */
    private Integer llmMaxAttempts = 3;
    /** LLM 重试基础退避毫秒。 */
    private Long llmRetryBaseDelayMs = 500L;
    /** LLM 重试退避上限毫秒。 */
    private Long llmRetryMaxDelayMs = 8000L;

    /** @return LLM 基础 URL */
    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    /** @param llmBaseUrl 设置 LLM 基础 URL */
    public void setLlmBaseUrl(String llmBaseUrl) {
        this.llmBaseUrl = llmBaseUrl;
    }

    /** @return LLM API Key */
    public String getLlmApiKey() {
        return llmApiKey;
    }

    /** @param llmApiKey 设置 LLM API Key */
    public void setLlmApiKey(String llmApiKey) {
        this.llmApiKey = llmApiKey;
    }

    /** @return 对话模型名 */
    public String getLlmChatModel() {
        return llmChatModel;
    }

    /** @param llmChatModel 设置对话模型名 */
    public void setLlmChatModel(String llmChatModel) {
        this.llmChatModel = llmChatModel;
    }

    /** @return Embedding 基础 URL */
    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    /** @param embeddingBaseUrl 设置 Embedding 基础 URL */
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    /** @return Embedding API Key */
    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    /** @param embeddingApiKey 设置 Embedding API Key */
    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    /** @return Embedding 模型名 */
    public String getEmbeddingModel() {
        return embeddingModel;
    }

    /** @param embeddingModel 设置 Embedding 模型名 */
    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /** @return Faiss 本地索引目录 */
    public String getFaissIndexDir() {
        return faissIndexDir;
    }

    /** @param faissIndexDir 设置 Faiss 本地索引目录 */
    public void setFaissIndexDir(String faissIndexDir) {
        this.faissIndexDir = faissIndexDir;
    }

    /** @return RAG top-K */
    public Integer getRagTopK() {
        return ragTopK;
    }

    /** @param ragTopK 设置 RAG top-K */
    public void setRagTopK(Integer ragTopK) {
        this.ragTopK = ragTopK;
    }

    /** @return RAG 分数阈值 */
    public Double getRagScoreThreshold() {
        return ragScoreThreshold;
    }

    /** @param ragScoreThreshold 设置分数阈值 */
    public void setRagScoreThreshold(Double ragScoreThreshold) {
        this.ragScoreThreshold = ragScoreThreshold;
    }

    /** @return 上下文最大字符数 */
    public Integer getRagMaxContextChars() {
        return ragMaxContextChars;
    }

    /** @param ragMaxContextChars 设置上下文最大字符数 */
    public void setRagMaxContextChars(Integer ragMaxContextChars) {
        this.ragMaxContextChars = ragMaxContextChars;
    }

    /** @return 日提问上限 */
    public Integer getDailyQuestionLimit() {
        return dailyQuestionLimit;
    }

    /** @param dailyQuestionLimit 设置日提问上限 */
    public void setDailyQuestionLimit(Integer dailyQuestionLimit) {
        this.dailyQuestionLimit = dailyQuestionLimit;
    }

    /** @return 默认客服知识库 ID */
    public Long getDefaultSupportKbId() {
        return defaultSupportKbId;
    }

    /** @param defaultSupportKbId 设置默认客服知识库 ID */
    public void setDefaultSupportKbId(Long defaultSupportKbId) {
        this.defaultSupportKbId = defaultSupportKbId;
    }

    /** @return 默认技术知识库 ID */
    public Long getDefaultTechnicalKbId() {
        return defaultTechnicalKbId;
    }

    /** @param defaultTechnicalKbId 设置默认技术知识库 ID */
    public void setDefaultTechnicalKbId(Long defaultTechnicalKbId) {
        this.defaultTechnicalKbId = defaultTechnicalKbId;
    }

    /** @return LLM 最大尝试次数 */
    public Integer getLlmMaxAttempts() {
        return llmMaxAttempts;
    }

    /** @param llmMaxAttempts 设置 LLM 最大尝试次数 */
    public void setLlmMaxAttempts(Integer llmMaxAttempts) {
        this.llmMaxAttempts = llmMaxAttempts;
    }

    /** @return LLM 重试基础退避毫秒 */
    public Long getLlmRetryBaseDelayMs() {
        return llmRetryBaseDelayMs;
    }

    /** @param llmRetryBaseDelayMs 设置基础退避 */
    public void setLlmRetryBaseDelayMs(Long llmRetryBaseDelayMs) {
        this.llmRetryBaseDelayMs = llmRetryBaseDelayMs;
    }

    /** @return LLM 重试退避上限毫秒 */
    public Long getLlmRetryMaxDelayMs() {
        return llmRetryMaxDelayMs;
    }

    /** @param llmRetryMaxDelayMs 设置退避上限 */
    public void setLlmRetryMaxDelayMs(Long llmRetryMaxDelayMs) {
        this.llmRetryMaxDelayMs = llmRetryMaxDelayMs;
    }
}
