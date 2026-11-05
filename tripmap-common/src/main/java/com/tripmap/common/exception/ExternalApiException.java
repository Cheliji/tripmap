package com.tripmap.common.exception;

import com.tripmap.common.result.ResultCode;

/**
 * 外部服务异常（任务 0.7 建立）
 *
 * 高德/天气/搜索/LLM 等外部 API 调用失败时抛出，统一由全局异常处理
 * 返回 4xxxxx 码段。携带 {@code supplier} 与 {@code retryable} 供日志
 * 定位与后续重试/降级决策（降级属设计内流程，不算系统故障）。
 */
public class ExternalApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 统一错误码（4xxxxx 外部服务码段） */
    private final int code;

    /** 供应商标识，如 "高德" / "和风" / "博查" / "DeepSeek" */
    private final String supplier;

    /** 是否可重试（超时/限流为 true；响应非法、密钥失效为 false） */
    private final boolean retryable;

    public ExternalApiException(ResultCode resultCode, String supplier, boolean retryable) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.supplier = supplier;
        this.retryable = retryable;
    }

    /** 外部码 + 自定义可读消息 */
    public ExternalApiException(int code, String message, String supplier, boolean retryable) {
        super(message);
        this.code = code;
        this.supplier = supplier;
        this.retryable = retryable;
    }

    public int getCode() {
        return code;
    }

    public String getSupplier() {
        return supplier;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
