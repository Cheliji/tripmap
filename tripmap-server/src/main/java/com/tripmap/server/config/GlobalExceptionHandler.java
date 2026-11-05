package com.tripmap.server.config;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.Result;
import com.tripmap.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理（任务 0.7 建立，AGENTS.md 第 6 节约定）
 *
 * 分层策略（参考阿里巴巴开发手册"异常处理"章节，按本项目规模收敛）：
 * <ul>
 *   <li>业务异常 {@link BizException}：预期内，warn 级（无堆栈），按码原样返回</li>
 *   <li>外部服务异常 {@link ExternalApiException}：warn 级，记录 supplier/retryable，按码返回</li>
 *   <li>框架级参数异常：映射到 1xxxxx 码段，消息拼装字段级中文提示</li>
 *   <li>未捕获异常兜底：error 级全堆栈进日志，对外固定脱敏文案（{@link ResultCode#SYSTEM_ERROR}）</li>
 * </ul>
 * 所有响应 HTTP 状态码恒为 200，错误信息一律走统一返回体 {@code code}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：预期内，不打印堆栈 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 外部服务异常：warn 级，堆栈信息进日志便于定位供应商问题 */
    @ExceptionHandler(ExternalApiException.class)
    public Result<Void> handleExternalApiException(ExternalApiException e) {
        if (e.isRetryable()) {
            log.warn("外部服务异常: supplier={}, retryable={}, code={}, message={}",
                    e.getSupplier(), true, e.getCode(), e.getMessage());
        } else {
            log.error("外部服务异常(不可重试): supplier={}, code={}, message={}",
                    e.getSupplier(), e.getCode(), e.getMessage(), e);
        }
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** JSR-303 @RequestBody 实体校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        return Result.fail(ResultCode.PARAM_VALIDATION_FAILED.getCode(), buildValidationMessage(e.getBindingResult().getFieldErrors()));
    }

    /** JSR-303 @ModelAttribute / 表单绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        return Result.fail(ResultCode.PARAM_VALIDATION_FAILED.getCode(), buildValidationMessage(e.getBindingResult().getFieldErrors()));
    }

    /** JSR-303 @RequestParam / 路径变量校验失败（需方法上 @Validated） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.PARAM_VALIDATION_FAILED.getCode(),
                ResultCode.PARAM_VALIDATION_FAILED.getMessage() + (detail.isEmpty() ? "" : ": " + detail));
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        return Result.fail(ResultCode.PARAM_MISSING.getCode(),
                ResultCode.PARAM_MISSING.getMessage() + ": " + e.getParameterName());
    }

    /** 请求体 JSON 解析失败 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(ResultCode.PARAM_ILLEGAL);
    }

    /** 参数类型不匹配（如数字参数收到非数字） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(ResultCode.PARAM_ILLEGAL.getCode(),
                ResultCode.PARAM_ILLEGAL.getMessage() + ": 参数 " + e.getName());
    }

    /** 路径无对应处理器（需 spring.mvc.throw-exception-if-no-handler-found=true） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        return Result.fail(ResultCode.RESOURCE_NOT_FOUND);
    }

    /** 请求方式不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ResultCode.METHOD_NOT_SUPPORTED);
    }

    /** 未捕获异常兜底：全堆栈进日志，对外脱敏 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未捕获系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }

    /** 拼装字段级校验提示："参数校验失败: 字段1 提示; 字段2 提示" */
    private String buildValidationMessage(java.util.List<FieldError> fieldErrors) {
        String detail = fieldErrors.stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResultCode.PARAM_VALIDATION_FAILED.getMessage() + (detail.isEmpty() ? "" : ": " + detail);
    }
}
