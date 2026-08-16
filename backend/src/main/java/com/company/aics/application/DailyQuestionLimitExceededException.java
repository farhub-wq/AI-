package com.company.aics.application;

/**
 * 当日用户提问次数超过配置上限时抛出，由 API 层映射为 HTTP 429。
 * 用于保护演示环境免受无限刷问。
 */
public class DailyQuestionLimitExceededException extends RuntimeException {

    /**
     * @param message 面向用户的限流说明
     */
    public DailyQuestionLimitExceededException(String message) {
        super(message);
    }
}
