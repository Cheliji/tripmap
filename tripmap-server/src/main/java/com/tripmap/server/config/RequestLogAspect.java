package com.tripmap.server.config;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求日志切面（任务 0.7 建立）
 *
 * 切 {@code com.tripmap.server.controller} 包下全部接口，记录 method、URI、
 * query、返回码与耗时，traceId 随 MDC 输出。日志分级约定：
 * <ul>
 *   <li>正常返回：info 级</li>
 *   <li>业务/外部服务异常：warn 级（不打印堆栈，属预期内路径）</li>
 *   <li>未捕获异常：error 级（全堆栈，与 GlobalExceptionHandler 的兜底日志互补）</li>
 * </ul>
 * 不记录请求体（避免自由文本输入灌爆日志；必要时按接口单独开 debug）。
 */
@Aspect
@Component
public class RequestLogAspect {

    private static final Logger log = LoggerFactory.getLogger(RequestLogAspect.class);

    @Around("execution(* com.tripmap.server.controller..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        HttpServletRequest request = currentRequest();
        String method = request == null ? "-" : request.getMethod();
        String uri = request == null ? "-" : request.getRequestURI();
        String query = request == null || request.getQueryString() == null ? "" : "?" + request.getQueryString();
        long start = System.currentTimeMillis();
        try {
            Object ret = pjp.proceed();
            long cost = System.currentTimeMillis() - start;
            if (ret instanceof Result<?> result) {
                log.info("[{}] {}{} -> code={}, 耗时={}ms", method, uri, query, result.getCode(), cost);
            } else {
                log.info("[{}] {}{} -> 非统一返回体(违反约定), 耗时={}ms", method, uri, query, cost);
            }
            return ret;
        } catch (BizException | ExternalApiException e) {
            // 多 catch 下公共上界为 RuntimeException，getCode() 需分别取值
            int code = e instanceof BizException biz ? biz.getCode() : ((ExternalApiException) e).getCode();
            long cost = System.currentTimeMillis() - start;
            log.warn("[{}] {}{} -> 预期内异常 code={}, message={}, 耗时={}ms", method, uri, query, code, e.getMessage(), cost);
            throw e;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - start;
            log.error("[{}] {}{} -> 系统异常, 耗时={}ms", method, uri, query, cost, e);
            throw e;
        }
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }
}
