package com.company.aics.api;

import com.company.aics.application.DailyQuestionLimitExceededException;
import com.company.aics.rag.AiServiceException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：将业务与校验异常映射为统一 {@link ApiEnvelope} 与对应 HTTP 状态码。
 * 覆盖日提问限流、AI 上游错误、参数非法、Bean/约束校验失败及未捕获异常。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 日提问次数超限 → HTTP 429，业务码 4290。
     */
    @ExceptionHandler(DailyQuestionLimitExceededException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleDailyQuestionLimit(DailyQuestionLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiEnvelope.failure(4290, ex.getMessage()));
    }

    /**
     * AI 链路错误：按类型映射限流/鉴权/超时/模型不可用等业务码。
     * 流式问答内部会捕获并降级，此处理器覆盖非流式或未捕获路径。
     */
    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleAiService(AiServiceException ex) {
        return switch (ex.getErrorType()) {
            case RATE_LIMIT -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiEnvelope.failure(4291, ex.getMessage()));
            case AUTH -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiEnvelope.failure(4011, ex.getMessage()));
            case TIMEOUT -> ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ApiEnvelope.failure(5040, ex.getMessage()));
            case MODEL_UNAVAILABLE, CONFIG -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiEnvelope.failure(5021, ex.getMessage()));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiEnvelope.failure(5020, ex.getMessage()));
        };
    }

    /**
     * 业务参数非法（如资源不存在、权限不足）→ HTTP 400，业务码 4001。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiEnvelope.failure(4001, ex.getMessage()));
    }

    /**
     * {@code @Valid} 请求体字段校验失败 → HTTP 400，业务码 4002。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        // 优先返回第一个字段错误，便于前端直接展示
        FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError == null ? "Request validation failed." : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiEnvelope.failure(4002, message));
    }

    /**
     * 方法参数约束校验失败 → HTTP 400，业务码 4003。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiEnvelope<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ApiEnvelope.failure(4003, ex.getMessage()));
    }

    /**
     * 未分类异常 → HTTP 500，业务码 5000（避免泄露堆栈，仅返回简要消息）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<Void>> handleException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.failure(5000, "Server error: " + ex.getMessage()));
    }
}
