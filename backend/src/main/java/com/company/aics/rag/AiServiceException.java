package com.company.aics.rag;

/**
 * AI 链路专用异常：区分超时、限流、鉴权、模型不可用等，便于上层降级与 API 返回。
 */
public class AiServiceException extends RuntimeException {

    /** 错误分类。 */
    private final ErrorType errorType;
    /** 可选的上游 HTTP 状态码。 */
    private final Integer httpStatus;
    /** 可选的建议重试等待（毫秒），通常来自 429 Retry-After。 */
    private final Long retryAfterMs;

    public AiServiceException(ErrorType errorType, String message) {
        this(errorType, message, null, null, null);
    }

    public AiServiceException(ErrorType errorType, String message, Throwable cause) {
        this(errorType, message, null, null, cause);
    }

    public AiServiceException(ErrorType errorType, String message, Integer httpStatus, Throwable cause) {
        this(errorType, message, httpStatus, null, cause);
    }

    public AiServiceException(
            ErrorType errorType,
            String message,
            Integer httpStatus,
            Long retryAfterMs,
            Throwable cause
    ) {
        super(message, cause);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
    }

    /** @return 错误分类 */
    public ErrorType getErrorType() {
        return errorType;
    }

    /** @return 上游 HTTP 状态码，可能为 null */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** @return 建议重试等待毫秒，可能为 null */
    public Long getRetryAfterMs() {
        return retryAfterMs;
    }

    /**
     * AI 调用失败的细分类别。
     */
    public enum ErrorType {
        /** 配置缺失或占位符未替换 */
        CONFIG,
        /** 连接/读超时 */
        TIMEOUT,
        /** 上游返回 429 等限流 */
        RATE_LIMIT,
        /** API Key 无效（401/403） */
        AUTH,
        /** 模型不存在或无权访问 */
        MODEL_UNAVAILABLE,
        /** 上游其它 4xx/5xx 或响应解析失败 */
        UPSTREAM,
        /** 本地向量库读写失败 */
        VECTOR_STORE
    }
}
