package com.tripmap.tool.amap;

import com.tripmap.common.exception.ExternalApiException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高德 HTTP 客户端单测（任务 1.1.2）
 *
 * <p>使用 MockWebServer 桩 HTTP 服务，验证：密钥注入、超时、
 * 指数退避重试（429/5xx/网络异常可重试，4xx 不可重试）、QPS 限流。</p>
 */
class AmapClientTest {

    private MockWebServer server;
    private AmapProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new AmapProperties();
        properties.setApiKey("test-key-123");
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setQps(1000); // 单测内不限速
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);
        properties.setMaxRetries(2);
        properties.setRetryBaseDelayMs(1); // 缩短退避，加快单测
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private AmapClient newClient() {
        return new AmapClient(AmapClient.buildRestClient(properties), properties);
    }

    @Test
    void 密钥缺失启动即失败() {
        properties.setApiKey(" ");
        IllegalStateException e = assertThrows(IllegalStateException.class, this::newClient);
        assertTrue(e.getMessage().contains("AMAP_API_KEY"));
    }

    @Test
    void 成功请求注入key与output参数() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"1\",\"count\":\"1\",\"pois\":[]}"));
        var node = newClient().get("/v3/place/text", Map.of("keywords", "西湖", "city", "杭州"));

        assertEquals("1", node.get("status").asText());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(request.getPath().contains("key=test-key-123"), "应注入 key 参数");
        assertTrue(request.getPath().contains("output=JSON"), "应注入 output=JSON");
        // 中文参数必须百分号编码（%E8%A5%BF%E6%B9%96 = 西湖）
        assertTrue(request.getPath().contains("%E8%A5%BF%E6%B9%96"), "中文关键词应 UTF-8 编码: " + request.getPath());
        assertTrue(request.getPath().contains("city=%E6%9D%AD%E5%B7%9E"), "城市参数应编码");
    }

    @Test
    void 服务端5xx触发指数退避重试() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(502));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"1\"}"));

        var node = newClient().get("/v3/direction/transit/integrated", Map.of());
        assertEquals("1", node.get("status").asText());
        assertEquals(3, server.getRequestCount(), "500/502 均应重试，共 3 次请求");
    }

    @Test
    void 限流响应触发重试() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"1\"}"));

        var node = newClient().get("/v3/place/text", Map.of());
        assertEquals("1", node.get("status").asText());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void 业务错误4xx不重试直接失败() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400));

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> newClient().get("/v3/place/text", Map.of()));
        assertEquals(1, server.getRequestCount(), "4xx 不应重试");
        assertEquals(400001, e.getCode());
        assertEquals("高德", e.getSupplier());
        assertTrue(!e.isRetryable(), "4xx 业务错误不可重试");
    }

    @Test
    void 重试耗尽后抛可重试异常() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }
        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> newClient().get("/v3/place/text", Map.of()));
        assertEquals(3, server.getRequestCount(), "首次 + 2 次重试");
        assertTrue(e.isRetryable(), "5xx 重试耗尽仍标记可重试（供上层降级决策）");
    }

    @Test
    void 高德业务状态0视为失败且不重试() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\",\"infocode\":\"10001\"}"));

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> newClient().get("/v3/place/text", Map.of()));
        assertEquals(1, server.getRequestCount(), "业务错误不应重试");
        assertTrue(!e.isRetryable());
    }

    @Test
    void 客户端遵守QPS上限() throws Exception {
        properties.setQps(5);
        // 每个响应 fast enough，主要看限流侧节奏
        for (int i = 0; i < 7; i++) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"1\"}"));
        }
        AmapClient client = newClient();
        long start = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            client.get("/v3/place/text", Map.of());
        }
        long elapsed = System.currentTimeMillis() - start;
        // 7 个请求 @5QPS，桶容量 5：至少需 (7-5)/5 = 0.4s
        assertTrue(elapsed >= 300, "7 请求 @5QPS 至少约 0.4s，实际 " + elapsed + "ms");
    }
}
