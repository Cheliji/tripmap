package com.tripmap.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 过滤器（任务 0.7 建立，与请求日志切面配套）
 *
 * 入口生成/透传链路追踪 ID：
 * <ul>
 *   <li>请求头 {@code X-Trace-Id} 有值则透传（便于上下游串联），无值则生成 UUID（去横线）</li>
 *   <li>写入 MDC，日志模板中以 %X{traceId} 输出；同时回写响应头，前端报障时可回传</li>
 *   <li>请求结束后清理 MDC，避免线程复用污染</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 请求头/响应头名 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** MDC 键名，logback 日志模板用 %X{traceId} 引用 */
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
