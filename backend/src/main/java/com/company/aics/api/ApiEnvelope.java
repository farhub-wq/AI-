package com.company.aics.api;

/**
 * 统一 API 响应信封：{@code code=0} 表示成功，失败时 {@code data} 为空并带业务错误码与消息。
 * 所有 REST 控制器成功/失败路径均通过本类型对外返回。
 */
public record ApiEnvelope<T>(int code, String message, T data) {

    /**
     * 构造成功响应。
     *
     * @param data 业务数据
     * @return code=0 的信封
     */
    public static <T> ApiEnvelope<T> success(T data) {
        return new ApiEnvelope<>(0, "ok", data);
    }

    /**
     * 构造失败响应。
     *
     * @param code    业务错误码
     * @param message 错误说明
     * @return data 为 null 的信封
     */
    public static <T> ApiEnvelope<T> failure(int code, String message) {
        return new ApiEnvelope<>(code, message, null);
    }
}
