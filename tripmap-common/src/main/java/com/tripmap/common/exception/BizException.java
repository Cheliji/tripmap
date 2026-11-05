package com.tripmap.common.exception;

import com.tripmap.common.result.ResultCode;

/**
 * 业务异常（任务 0.7 建立）
 *
 * 预期内的业务失败（如参数校验失败、城市不在白名单、规划无可行解），
 * 属于正常流程的一部分：全局异常处理按 {@link #getCode()} 原样返回，
 * 不打印 error 级日志（区别于未捕获系统异常）。
 * 参考阿里巴巴 Java 开发手册：业务异常使用非受检异常，不用于流程控制。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 统一错误码（{@link ResultCode} 码段内取值） */
    private final int code;

    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    /** 业务码 + 自定义可读消息（如带回具体失败原因） */
    public BizException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
