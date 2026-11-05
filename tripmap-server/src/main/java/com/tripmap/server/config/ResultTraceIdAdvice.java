package com.tripmap.server.config;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.tripmap.common.result.Result;

/**
 * 统一返回体 traceId 填充（任务 0.7 建立）
 *
 * 拦截所有 {@link Result} 响应（含全局异常处理的返回），若 traceId 为空则从 MDC 补齐，
 * 保证成功/失败响应均可凭 traceId 反查请求日志。
 * 注意：不得限定 basePackages——@ExceptionHandler 的返回按异常处理类本身判定包归属，
 * 限定包会导致全局异常处理的响应（如 404）漏掉增强。
 */
@RestControllerAdvice
public class ResultTraceIdAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result<?> result && !StringUtils.hasText(result.getTraceId())) {
            result.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        }
        return body;
    }
}
