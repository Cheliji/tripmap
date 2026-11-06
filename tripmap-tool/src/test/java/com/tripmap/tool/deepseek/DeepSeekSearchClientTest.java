package com.tripmap.tool.deepseek;

import com.tripmap.common.exception.ExternalApiException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 联网搜索客户端单测（tripmap-poi功能设计说明书 §4.8）
 *
 * <p>MockWebServer 打桩，验证：Bearer 鉴权、请求体口径（query/count/need_content）、
 * 重试口径（仅 408/429/5xx 与网络异常退避，其余 4xx 不重试）、
 * 响应解析（results[] 字段映射/空结果）、最小调用间隔限速。</p>
 */
class DeepSeekSearchClientTest {

    private MockWebServer server;
    private DeepSeekProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new DeepSeekProperties();
        properties.setApiKey("test-key-123");
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setConnectTimeoutMs(1000);
        properties.setSearchReadTimeoutMs(1000);
        properties.setMaxRetries(2);
        properties.setRetryBaseDelayMs(1);
        properties.setSearchMinIntervalMs(0);
        properties.setSearchCount(10);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private DeepSeekSearchClient newClient() {
        return new DeepSeekSearchClient(DeepSeekSearchClient.buildRestClient(properties), properties);
    }

    @Test
    void 密钥缺失启动即失败() {
        properties.setApiKey(" ");
        IllegalStateException e = assertThrows(IllegalStateException.class, this::newClient);
        assertTrue(e.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void 成功请求携带Bearer与请求体口径() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""
                {"results": [
                  {"title": "西湖官网", "url": "https://a.com/x", "summary": "摘要A",
                   "content": "正文A", "rerank_score": 2.1, "authority": "高"},
                  {"title": "攻略", "url": "https://b.com/y", "rerank_score": 0.5}
                ]}"""));

        List<DeepSeekSearchClient.SearchResultItem> items = newClient().search("西湖 开放时间");

        assertEquals(2, items.size());
        assertEquals("西湖官网", items.get(0).title());
        assertEquals("https://a.com/x", items.get(0).url());
        assertEquals("摘要A", items.get(0).summary());
        assertEquals("正文A", items.get(0).content());
        assertEquals(2.1, items.get(0).rerankScore());
        assertEquals(0.5, items.get(1).rerankScore(), "缺省 rerank_score 应为 0");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("Bearer test-key-123", request.getHeader("Authorization"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"query\":\"西湖 开放时间\""), "请求体应含 query: " + body);
        assertTrue(body.contains("\"count\":10"), "请求体应含 count: " + body);
        assertTrue(body.contains("\"need_content\":true"), "请求体应含 need_content: " + body);
    }

    @Test
    void 结果为空返回空列表不算失败() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"results\": []}"));
        assertTrue(newClient().search("查无此地").isEmpty());
    }

    @Test
    void 限流429触发重试() {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"results\": []}"));
        assertTrue(newClient().search("q").isEmpty());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void 服务端5xx重试耗尽后抛可重试异常() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(502));
        server.enqueue(new MockResponse().setResponseCode(503));
        ExternalApiException e = assertThrows(ExternalApiException.class, () -> newClient().search("q"));
        assertTrue(e.isRetryable());
        assertEquals(3, server.getRequestCount(), "首次 + 2 次重试");
    }

    @Test
    void 其余4xx不重试直接失败() {
        server.enqueue(new MockResponse().setResponseCode(400));
        ExternalApiException e = assertThrows(ExternalApiException.class, () -> newClient().search("q"));
        assertTrue(!e.isRetryable());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void 响应缺results数组视为失败() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\": 1}"));
        assertThrows(ExternalApiException.class, () -> newClient().search("q"));
    }

    @Test
    void 响应非法JSON不重试() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));
        ExternalApiException e = assertThrows(ExternalApiException.class, () -> newClient().search("q"));
        assertTrue(!e.isRetryable());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void 时间范围过滤配置后发送空白不发送() throws Exception {
        // 配置时：请求体携带 time_range
        properties.setSearchTimeRange("past_year");
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"results\": []}"));
        newClient().search("q");
        String body = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(body.contains("\"time_range\":\"past_year\""), "配置后应发送 time_range: " + body);

        // 空白时：请求体不携带（默认不过滤）
        properties.setSearchTimeRange(" ");
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"results\": []}"));
        newClient().search("q");
        String body2 = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(!body2.contains("time_range"), "默认不应发送 time_range: " + body2);
    }

    @Test
    void 最小调用间隔限速生效() {
        properties.setSearchMinIntervalMs(300);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"results\": []}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"results\": []}"));
        DeepSeekSearchClient client = newClient();
        client.search("a");
        long start = System.nanoTime();
        client.search("b");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 250, "第二次调用应等待最小间隔，实际 " + elapsedMs + " ms");
    }
}
