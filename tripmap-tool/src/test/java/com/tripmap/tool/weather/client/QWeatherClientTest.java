package com.tripmap.tool.weather.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.tool.weather.config.QWeatherProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 和风 HTTP 客户端单测（任务 2.3.1）。
 *
 * <p>使用 MockWebServer 桩 HTTP 服务，验证：Host/Key 缺失 fail-fast、密钥头注入、
 * 坐标路径与参数、gzip 解压、指数退避重试（429/5xx 可重试，4xx 不可重试）。</p>
 */
class QWeatherClientTest {

    private MockWebServer server;
    private QWeatherProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new QWeatherProperties();
        properties.setApiKey("test-key-123");
        properties.setApiHost(server.url("/").toString().replaceAll("/$", ""));
        properties.setQpm(1000); // 单测内不限速
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);
        properties.setMaxRetries(2);
        properties.setRetryBaseDelayMs(1); // 缩短退避，加快单测
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private QWeatherClient newClient() {
        return new QWeatherClient(QWeatherClient.buildRestClient(properties), properties);
    }

    /** gzip 压缩（和风所有响应 gzip 压缩，接口文档 §2.3）；MockWebServer 需 okio.Buffer */
    private okio.Buffer gzip(String s) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return new okio.Buffer().write(bos.toByteArray());
    }

    @Test
    void 密钥缺失启动即失败() {
        properties.setApiKey(" ");
        IllegalStateException e = assertThrows(IllegalStateException.class, this::newClient);
        assertTrue(e.getMessage().contains("QWEATHER_API_KEY"));
    }

    @Test
    void host缺失启动即失败() {
        properties.setApiHost(" ");
        IllegalStateException e = assertThrows(IllegalStateException.class, this::newClient);
        assertTrue(e.getMessage().contains("QWEATHER_API_HOST"));
    }

    @Test
    void 成功请求注入密钥头与坐标路径并解压gzip() throws Exception {
        String body = "{\"days\":[{\"forecastStartTime\":\"2026-11-19T06:00+08:00\"," +
                "\"temperatureMax\":{\"value\":20.0}}]}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzip(body)));

        JsonNode node = newClient().getDailyForecast(30.25, 120.15, 3);

        assertEquals("20.0", node.path("days").get(0).path("temperatureMax").path("value").asText(),
                "gzip 响应应被正确解压");
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertTrue(request.getPath().contains("/weather/v1/daily/30.25/120.15"),
                "坐标应在路径参数中: " + request.getPath());
        assertTrue(request.getPath().contains("days=3"), "days 参数: " + request.getPath());
        assertTrue(request.getPath().contains("localTime=true"), "localTime=true: " + request.getPath());
        assertTrue(request.getPath().contains("lang=zh"), "lang=zh: " + request.getPath());
        assertEquals("test-key-123", request.getHeader("X-QW-Api-Key"), "密钥应走请求头");
    }

    @Test
    void 服务端5xx触发指数退避重试() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(502));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"days\":[]}"));

        JsonNode node = newClient().getDailyForecast(30.25, 120.15, 1);
        assertTrue(node.path("days").isArray());
        assertEquals(3, server.getRequestCount(), "500/502 均应重试，共 3 次请求");
    }

    @Test
    void 限流429触发重试() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"days\":[]}"));

        JsonNode node = newClient().getDailyForecast(30.25, 120.15, 1);
        assertTrue(node.path("days").isArray());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void 业务错误4xx不重试直接失败() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"title\":\"Unauthorized\"}}"));

        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> newClient().getDailyForecast(30.25, 120.15, 1));
        assertEquals(1, server.getRequestCount(), "4xx 不应重试");
        assertEquals(400002, e.getCode());
        assertEquals("和风", e.getSupplier());
        assertFalse(e.isRetryable(), "4xx 业务错误不可重试");
    }

    @Test
    void 重试耗尽后抛可重试异常() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(503));
        }
        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> newClient().getDailyForecast(30.25, 120.15, 1));
        assertEquals(3, server.getRequestCount(), "首次 + 2 次重试");
        assertTrue(e.isRetryable(), "5xx 重试耗尽仍标记可重试（供上层降级决策）");
    }

    @Test
    void 响应非法JSON不可重试() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("not-json"));
        ExternalApiException e = assertThrows(ExternalApiException.class,
                () -> newClient().getDailyForecast(30.25, 120.15, 1));
        assertFalse(e.isRetryable(), "非法 JSON 不可重试");
    }
}
