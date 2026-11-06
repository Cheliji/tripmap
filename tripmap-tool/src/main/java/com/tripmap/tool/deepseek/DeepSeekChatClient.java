package com.tripmap.tool.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DeepSeek 对话补全薄客户端（LLM 结构化整理用，tripmap-poi功能设计说明书 §4.8）
 *
 * <p>{@code POST {baseUrl}/chat/completions}（OpenAI 兼容协议），Bearer 鉴权；
 * 强制 {@code response_format={"type":"json_object"}} 约束 JSON 输出。</p>
 *
 * <p>本类是"薄客户端"：M3 任务 3.1.1 的统一 ChatClient 封装就绪后合并对齐，
 * 两侧（tripmap-poi 功能二与运行时节点）先行复用本实现。</p>
 *
 * <p>重试：仅 429/5xx/408 与网络异常指数退避重试，其余 4xx 不重试
 * （传输层重试不违反"每条 POI = 1 次 LLM 调用"的逻辑口径，§4.2）。</p>
 */
public class DeepSeekChatClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatClient.class);

    /** 供应商标识（ExternalApiException.supplier，日志与降级展示用） */
    public static final String SUPPLIER = "DeepSeek";

    private static final String CHAT_PATH = "/chat/completions";

    private final RestClient restClient;
    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeepSeekChatClient(RestClient restClient, DeepSeekProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                    "DeepSeek API Key 未配置：请设置环境变量 DEEPSEEK_API_KEY（禁止硬编码/入库，AGENTS.md 第 5 节）");
        }
    }

    /**
     * 单次结构化整理调用：system + user 两轮消息，返回模型输出的 JSON 文本。
     *
     * @param systemPrompt 系统提示词（整理规则、受控词表等）
     * @param userMessage  用户消息（POI 高德字段 + 搜索素材）
     * @return 模型输出文本（预期为合法 JSON，解析由调用方负责）
     * @throws ExternalApiException 可重试错误耗尽后抛出（retryable=true），
     *                              不可重试错误（4xx/响应非法）立即抛出（retryable=false）
     */
    public String chatJson(String systemPrompt, String userMessage) {
        String body = buildRequestBody(systemPrompt, userMessage);
        int maxAttempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; ; attempt++) {
            try {
                String response = restClient.post()
                        .uri(properties.getBaseUrl() + CHAT_PATH)
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return extractContent(response);
            } catch (RestClientResponseException e) {
                if (isRetryableHttpStatus(e.getStatusCode())) {
                    if (attempt < maxAttempts) {
                        backoff(attempt);
                        continue;
                    }
                    throw new ExternalApiException(ResultCode.EXTERNAL_LLM, SUPPLIER, true);
                }
                throw new ExternalApiException(ResultCode.EXTERNAL_LLM.getCode(),
                        "DeepSeek LLM 调用失败(" + e.getStatusCode().value() + ")", SUPPLIER, false);
            } catch (org.springframework.web.client.ResourceAccessException e) {
                // 超时 / IO 异常：可重试
                if (attempt < maxAttempts) {
                    log.warn("DeepSeek LLM 网络异常（第 {}/{} 次），准备重试: {}", attempt, maxAttempts, e.getMessage());
                    backoff(attempt);
                    continue;
                }
                throw new ExternalApiException(ResultCode.EXTERNAL_LLM, SUPPLIER, true);
            }
        }
    }

    private String buildRequestBody(String systemPrompt, String userMessage) {
        try {
            ObjectMapper om = objectMapper;
            JsonNode messages = om.createArrayNode()
                    .add(om.createObjectNode().put("role", "system").put("content", systemPrompt))
                    .add(om.createObjectNode().put("role", "user").put("content", userMessage));
            ObjectNode root = om.createObjectNode();
            root.put("model", properties.getChatModel());
            root.set("messages", messages);
            root.put("temperature", properties.getChatTemperature());
            root.set("response_format", om.createObjectNode().put("type", "json_object"));
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException("LLM 请求体构建失败", e);
        }
    }

    /** 提取 choices[0].message.content；结构非法（无 choices/无 content）视为不可重试失败 */
    private String extractContent(String response) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (IOException e) {
            log.error("DeepSeek LLM 响应解析失败: {}", response, e);
            throw new ExternalApiException(ResultCode.EXTERNAL_LLM, SUPPLIER, false);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            log.error("DeepSeek LLM 响应缺少 choices: {}", response);
            throw new ExternalApiException(ResultCode.EXTERNAL_LLM, SUPPLIER, false);
        }
        JsonNode content = choices.get(0).path("message").get("content");
        if (content == null || content.isNull() || !StringUtils.hasText(content.asText())) {
            log.error("DeepSeek LLM 响应缺少 message.content: {}", response);
            throw new ExternalApiException(ResultCode.EXTERNAL_LLM, SUPPLIER, false);
        }
        return content.asText();
    }

    private boolean isRetryableHttpStatus(HttpStatusCode status) {
        return status.value() == 408 || status.value() == 429 || status.is5xxServerError();
    }

    /** 指数退避：base * 2^(attempt-1) + [0, base) 抖动 */
    private void backoff(int attempt) {
        long base = properties.getRetryBaseDelayMs();
        long delay = base * (1L << (attempt - 1))
                + ThreadLocalRandom.current().nextLong(Math.max(1, base));
        log.warn("DeepSeek LLM 可重试错误，第 {} 次重试，退避 {} ms", attempt, delay);
        sleepNanos(delay * 1_000_000L);
    }

    private static void sleepNanos(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
    }

    /**
     * 构建带超时控制的 RestClient（JDK HttpClient，自带连接池，理由同 AmapClient）。
     * LLM 读取超时独立于搜索（60s vs 10s），故工厂与搜索客户端分开。
     */
    public static RestClient buildRestClient(DeepSeekProperties properties) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getChatReadTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
