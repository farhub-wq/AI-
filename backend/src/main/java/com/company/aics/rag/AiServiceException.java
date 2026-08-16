package com.company.aics.rag;

/**
 * AI 链路专用异常：区分超时、限流、鉴权、模型不可用等，便于上层降级与 API 返回。
 */
public class AiServiceException extends RuntimeException {

    /** 错误分类。 */
    private final ErrorType errorType;
    /** 可选的上游 HTTP 状态码。 */
    private final Integer httpStatus;

    /**
     * 构造不含 cause / HTTP 状态的异常。
     *
     * @param errorType 错误类型
     * @param message   说明信息
     */
    public AiServiceException(ErrorType errorType, String message) {
        this(errorType, message, null, null);
    }

    /**
     * 构造带 cause 的异常。
     *
     * @param errorType 错误类型
     * @param message   说明信息
     * @param cause     原始异常
     */
    public AiServiceException(ErrorType errorType, String message, Throwable cause) {
        this(errorType, message, null, cause);
    }

    /**
     * 完整构造：类型、消息、上游状态码与 cause。
     *
     * @param errorType  错误类型
     * @param message    说明信息
     * @param httpStatus 上游 HTTP 状态，可空
     * @param cause      原始异常，可空
     */
    public AiServiceException(ErrorType errorType, String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }

    /** @return 错误分类 */
    public ErrorType getErrorType() {
        return errorType;
    }

    /** @return 上游 HTTP 状态码，可能为 null */
    public Integer getHttpStatus() {
        return httpStatus;
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
