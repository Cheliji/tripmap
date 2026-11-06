package com.tripmap.tool.deepseek;

import com.tripmap.common.exception.ExternalApiException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek LLM 薄客户端单测（tripmap-poi功能设计说明书 §4.8）
 *
 * <p>MockWebServer 打桩，验证：json_object 强制输出约束、请求体口径
 *（model/messages/temperature/response_format）、choices[0].message.content 提取、
 * 重试口径（429/5xx 退避，其余 4xx 不重试）。</p>
 */
class DeepSeekChatClientTest {

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
        properties.setChatReadTimeoutMs(1000);
        properties.setMaxRetries(2);
        properties.setRetryBaseDelayMs(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private DeepSeekChatClient newClient() {
        return new DeepSeekChatClient(DeepSeekChatClient.buildRestClient(properties), properties);
    }

    @Test
    void 密钥缺失启动即失败() {
        properties.setApiKey(null);
        IllegalStateException e = assertThrows(IllegalStateException.class, this::newClient);
        assertTrue(e.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void 成功请求与响应提取() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"choices": [{"message": {"role": "assistant", "content": "{\\"summary\\": \\"ok\\"}"}}],
                 "usage": {"prompt_tokens": 10}}"""));

        String content = newClient().chatJson("系统提示", "用户消息");

        assertEquals("{\"summary\": \"ok\"}", content);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("Bearer test-key-123", request.getHeader("Authorization"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"deepseek-chat\""), "应带模型: " + body);
        assertTrue(body.contains("\"role\":\"system\"") && body.contains("系统提示"), "应含 system 消息: " + body);
        assertTrue(body.contains("\"role\":\"user\"") && body.contains("用户消息"), "应含 user 消息: " + body);
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"), "应强制 JSON 输出: " + body);
        assertTrue(body.contains("\"temperature\":0.2"), "应带温度参数: " + body);
    }

    @Test
    void 限流429触发重试后成功() {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"choices\": [{\"message\": {\"content\": \"{}\"}}]}"));
        assertEquals("{}", newClient().chatJson("s", "u"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void 服务端5xx重试耗尽抛可重试异常() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        ExternalApiException e = assertThrows(ExternalApiException.class, () -> newClient().chatJson("s", "u"));
        assertTrue(e.isRetryable());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void 密钥401不重试直接失败() {
        server.enqueue(new MockResponse().setResponseCode(401));
        ExternalApiException e = assertThrows(ExternalApiException.class, () -> newClient().chatJson("s", "u"));
        assertTrue(!e.isRetryable());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void 响应缺choices视为失败且不重试() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"error\": \"x\"}"));
        ExternalApiException e = assertThrows(ExternalApiException.class, () -> newClient().chatJson("s", "u"));
        assertTrue(!e.isRetryable());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void 响应content为空视为失败() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"choices\": [{\"message\": {\"content\": \"\"}}]}"));
        assertThrows(ExternalApiException.class, () -> newClient().chatJson("s", "u"));
    }
}
