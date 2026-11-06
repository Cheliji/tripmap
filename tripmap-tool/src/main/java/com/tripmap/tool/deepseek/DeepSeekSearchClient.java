package com.tripmap.tool.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DeepSeek 官方联网搜索薄客户端（TASKS 2.7.1 / tripmap-poi功能设计说明书 §4.8）
 *
 * <p>{@code POST {baseUrl}/v1/tools/search}，Bearer 鉴权。请求只带
 * {@code query}/{@code count}/{@code need_content} 三个确定字段；
 * {@code ori_question}/{@code localization} 属可选增强，本模块用不上不发送。</p>
 *
 * <p>重试口径按官方文档（2026-11-05 项目方提供）：<b>仅 408/429/5xx 与网络异常退避重试，
 * 其余 4xx 不重试</b>；结果为空不计费且不算失败（返回空列表）。
 * 客户端内置最小调用间隔限速（默认 ≥1 次/秒，保护配额）。</p>
 *
 * <p>不使用 Fetch 接口：Search 开启 need_content 已返回网页正文，与 Fetch 产物重叠，
 * 且 Fetch 有域名级 QPS 与 404 拦截风险（§4.8）。</p>
 */
public class DeepSeekSearchClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSearchClient.class);

    /** 供应商标识（ExternalApiException.supplier，日志与降级展示用） */
    public static final String SUPPLIER = "DeepSeek";

    private static final String SEARCH_PATH = "/v1/tools/search";

    private final RestClient restClient;
    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 上次请求开始时间（System.nanoTime），用于最小调用间隔限速 */
    private long lastCallNanos = 0L;

    public DeepSeekSearchClient(RestClient restClient, DeepSeekProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        if (!StringUtils.hasText(properties.getApiKey())) {
            // 与高德配置同款 fail-fast 约定：密钥缺失启动即失败，避免运行期才暴露
            throw new IllegalStateException(
                    "DeepSeek API Key 未配置：请设置环境变量 DEEPSEEK_API_KEY（禁止硬编码/入库，AGENTS.md 第 5 节）");
        }
    }

    /**
     * 执行一次联网搜索。
     *
     * @param query 查询词（1~128 字符，调用方负责控制长度）
     * @return 结果列表（按官方返回顺序，未重排；空结果返回空列表）
     * @throws ExternalApiException 可重试错误耗尽后抛出（retryable=true），
     *                              不可重试错误（4xx/响应非法）立即抛出（retryable=false）
     */
    public List<SearchResultItem> search(String query) {
        String body = buildRequestBody(query);
        int maxAttempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; ; attempt++) {
            throttle();
            try {
                String response = restClient.post()
                        .uri(properties.getBaseUrl() + SEARCH_PATH)
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parseResponse(response);
            } catch (RestClientResponseException e) {
                if (isRetryableHttpStatus(e.getStatusCode())) {
                    if (attempt < maxAttempts) {
                        backoff(attempt);
                        continue;
                    }
                    throw new ExternalApiException(ResultCode.EXTERNAL_SEARCH, SUPPLIER, true);
                }
                throw new ExternalApiException(ResultCode.EXTERNAL_SEARCH.getCode(),
                        "DeepSeek 搜索调用失败(" + e.getStatusCode().value() + ")", SUPPLIER, false);
            } catch (org.springframework.web.client.ResourceAccessException e) {
                // 超时 / IO 异常：可重试
                if (attempt < maxAttempts) {
                    log.warn("DeepSeek 搜索网络异常（第 {}/{} 次），准备重试: {}", attempt, maxAttempts, e.getMessage());
                    backoff(attempt);
                    continue;
                }
                throw new ExternalApiException(ResultCode.EXTERNAL_SEARCH, SUPPLIER, true);
            }
        }
    }

    private String buildRequestBody(String query) {
        try {
            return objectMapper.createObjectNode()
                    .put("query", query)
                    .put("count", properties.getSearchCount())
                    .put("need_content", true)
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException("搜索请求体构建失败", e);
        }
    }

    /**
     * 解析响应：取 {@code results[]} 的 title/url/summary/content/rerank_score。
     * 结果为空数组 → 空列表（官方口径：结果为空不计费，不算失败）。
     */
    private List<SearchResultItem> parseResponse(String response) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (IOException e) {
            log.error("DeepSeek 搜索响应解析失败: {}", response, e);
            throw new ExternalApiException(ResultCode.EXTERNAL_SEARCH, SUPPLIER, false);
        }
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            log.error("DeepSeek 搜索响应缺少 results 数组: {}", response);
            throw new ExternalApiException(ResultCode.EXTERNAL_SEARCH, SUPPLIER, false);
        }
        List<SearchResultItem> items = new ArrayList<>();
        for (JsonNode node : results) {
            items.add(new SearchResultItem(
                    textOrNull(node.get("title")),
                    textOrNull(node.get("url")),
                    textOrNull(node.get("summary")),
                    textOrNull(node.get("content")),
                    node.path("rerank_score").asDouble(0.0)));
        }
        return items;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 最小调用间隔限速：距上次请求不足间隔则睡眠补足（保护配额，§4.8） */
    private void throttle() {
        long now = System.nanoTime();
        long minGapNanos = properties.getSearchMinIntervalMs() * 1_000_000L;
        long elapsed = now - lastCallNanos;
        if (lastCallNanos != 0L && elapsed < minGapNanos) {
            sleepNanos(minGapNanos - elapsed);
        }
        lastCallNanos = System.nanoTime();
    }

    private boolean isRetryableHttpStatus(HttpStatusCode status) {
        return status.value() == 408 || status.value() == 429 || status.is5xxServerError();
    }

    /** 指数退避：base * 2^(attempt-1) + [0, base) 抖动 */
    private void backoff(int attempt) {
        long base = properties.getRetryBaseDelayMs();
        long delay = base * (1L << (attempt - 1))
                + ThreadLocalRandom.current().nextLong(Math.max(1, base));
        log.warn("DeepSeek 搜索可重试错误，第 {} 次重试，退避 {} ms", attempt, delay);
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
     */
    public static RestClient buildRestClient(DeepSeekProperties properties) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getSearchReadTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /** 搜索结果条目（素材载体，挑选与截断由 tripmap-poi 的 SearchMaterialPicker 负责） */
    public record SearchResultItem(String title, String url, String summary, String content,
                                   double rerankScore) {
    }
}
