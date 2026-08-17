package com.company.aics.rag;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 调用重试执行器：对超时 / 限流 / 可恢复上游错误做指数退避 + 抖动；
 * 鉴权、配置、模型不可用等不可恢复错误立即失败，交给上层降级。
 */
public final class LlmCallRetry {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRetry.class);

    private LlmCallRetry() {
    }

    /**
     * 带重试执行；{@code attempts} 含首次调用。
     *
     * @param operation 日志用操作名
     * @param attempts  最大尝试次数（≥1）
     * @param baseDelayMs 基础退避毫秒
     * @param maxDelayMs  退避上限毫秒
     * @param action      实际调用
     */
    public static <T> T execute(
            String operation,
            int attempts,
            long baseDelayMs,
            long maxDelayMs,
            Supplier<T> action
    ) {
        int maxAttempts = Math.max(1, attempts);
        AtomicInteger tryNo = new AtomicInteger(0);
        RuntimeException last = null;
        while (tryNo.incrementAndGet() <= maxAttempts) {
            int current = tryNo.get();
            try {
                return action.get();
            } catch (RuntimeException ex) {
                last = ex;
                AiServiceException classified = classify(ex);
                if (!isRetryable(classified) || current >= maxAttempts) {
                    throw classified != null ? classified : ex;
                }
                long sleepMs = computeDelayMs(current, baseDelayMs, maxDelayMs, classified);
                log.warn(
                        "LLM {} attempt {}/{} failed ({}), retry in {} ms: {}",
                        operation,
                        current,
                        maxAttempts,
                        classified.getErrorType(),
                        sleepMs,
                        classified.getMessage()
                );
                sleep(sleepMs);
            }
        }
        throw last == null ? new IllegalStateException("LLM retry exhausted: " + operation) : last;
    }

    /**
     * 无返回值版本。
     */
    public static void run(
            String operation,
            int attempts,
            long baseDelayMs,
            long maxDelayMs,
            Runnable action
    ) {
        execute(operation, attempts, baseDelayMs, maxDelayMs, () -> {
            action.run();
            return null;
        });
    }

    /** 是否应对该错误重试。 */
    public static boolean isRetryable(Throwable error) {
        AiServiceException classified = classify(error);
        if (classified == null) {
            return false;
        }
        return switch (classified.getErrorType()) {
            case TIMEOUT, RATE_LIMIT -> true;
            case UPSTREAM -> {
                Integer status = classified.getHttpStatus();
                yield status != null && status >= 500;
            }
            default -> false;
        };
    }

    /**
     * 将 IO / 包装异常归一为 {@link AiServiceException}（已是则原样返回）。
     */
    public static AiServiceException classify(Throwable error) {
        if (error == null) {
            return null;
        }
        if (error instanceof AiServiceException ai) {
            return ai;
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof AiServiceException ai) {
                return ai;
            }
            if (cursor instanceof SocketTimeoutException
                    || cursor instanceof InterruptedIOException
                    || (cursor.getMessage() != null && cursor.getMessage().toLowerCase().contains("timeout"))) {
                return new AiServiceException(
                        AiServiceException.ErrorType.TIMEOUT,
                        "LLM 请求超时: " + cursor.getMessage(),
                        cursor
                );
            }
            cursor = cursor.getCause();
        }
        if (error instanceof RuntimeException runtime) {
            return new AiServiceException(
                    AiServiceException.ErrorType.UPSTREAM,
                    runtime.getMessage() == null ? "LLM 调用失败" : runtime.getMessage(),
                    runtime
            );
        }
        return new AiServiceException(
                AiServiceException.ErrorType.UPSTREAM,
                error.getMessage() == null ? "LLM 调用失败" : error.getMessage(),
                error
        );
    }

    /**
     * 指数退避：base * 2^(attempt-1)，加 0–25% 抖动，并尊重 429 的 Retry-After。
     */
    public static long computeDelayMs(int attempt, long baseDelayMs, long maxDelayMs, AiServiceException error) {
        long base = Math.max(50L, baseDelayMs);
        long cap = Math.max(base, maxDelayMs);
        long exponential = base * (1L << Math.min(8, Math.max(0, attempt - 1)));
        long delay = Math.min(cap, exponential);
        if (error != null && error.getRetryAfterMs() != null && error.getRetryAfterMs() > 0) {
            delay = Math.max(delay, Math.min(cap, error.getRetryAfterMs()));
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, delay / 4 + 1));
        return Math.min(cap, delay + jitter);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(AiServiceException.ErrorType.TIMEOUT, "LLM 重试等待被中断。", ex);
        }
    }
}
