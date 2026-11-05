package com.tripmap.common.result;

import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;

/**
 * 统一返回体（任务 0.7 建立，AGENTS.md 第 6 节约定）
 *
 * 约定：
 * <ul>
 *   <li>{@code code} 恒为 number，0 成功、非 0 失败；前端以 {@code resp.code === 0} 判断，不依赖 HTTP 状态码（错误也返回 HTTP 200）</li>
 *   <li>{@code message} 成功固定 "ok"；失败为可读中文，由后端控制，禁止直接透出框架原始英文异常信息</li>
 *   <li>{@code data} 失败时恒为 null</li>
 *   <li>{@code traceId} 由接入层 TraceIdFilter/ResultTraceIdAdvice 填充，与请求日志一一对应，报障时凭此查日志</li>
 * </ul>
 * 所有 Controller 接口必须返回本类型，禁止裸业务对象/裸 Map 直接返回。
 *
 * @param <T> 业务数据类型
 */
@Schema(description = "统一返回体")
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "错误码，0 表示成功；码段约定见 AGENTS.md 第 6 节", example = "0")
    private int code;

    @Schema(description = "提示信息，成功固定为 ok，失败为可读中文", example = "ok")
    private String message;

    @Schema(description = "业务数据，失败时恒为 null")
    private T data;

    @Schema(description = "链路追踪 ID，与请求日志一一对应", example = "a1b2c3d4e5f6")
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS.getCode();
        result.message = ResultCode.SUCCESS.getMessage();
        result.data = data;
        return result;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
