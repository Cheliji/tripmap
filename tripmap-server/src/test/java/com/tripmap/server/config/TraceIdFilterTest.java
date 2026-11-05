package com.tripmap.server.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * traceId 过滤器测试（任务 0.7）
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void 应透传请求头中的traceId并写入MDC与响应头() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertEquals("trace-abc-123", mdcDuringChain.get());
        assertEquals("trace-abc-123", response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
        // 请求结束后必须清理 MDC，避免线程复用污染
        assertNull(MDC.get(TraceIdFilter.MDC_KEY));
    }

    @Test
    void 无traceId请求头时应自动生成并回写响应头() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertNotNull(mdcDuringChain.get());
        assertTrue(mdcDuringChain.get().length() >= 32, "自动生成的 traceId 应为去横线 UUID");
        assertEquals(mdcDuringChain.get(), response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
        assertNull(MDC.get(TraceIdFilter.MDC_KEY));
    }
}
