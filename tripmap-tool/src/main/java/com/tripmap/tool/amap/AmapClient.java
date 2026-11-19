package com.tripmap.tool.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.util.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 高德开放平台 HTTP 客户端（任务 1.1.2）
 *
 * <p>统一承担：密钥注入（key 参数）、QPS 限流（令牌桶）、超时控制（连接/读取）、
 * 失败重试（指数退避 + 抖动）。业务侧只关心路径与业务参数，
 * POI 搜索（1.1.3）、路径规划（2.4.1）等在此之上封装。</p>
 *
 * <p>重试策略（指数退避）：对<b>可重试</b>错误重试——网络超时/IO 异常、HTTP 429（限流）、
 * 5xx（服务端错误），以及<b>高德业务级限流</b>（status=0 且 infocode=10021 QPS 超限 /
 * 10004 访问过于频繁，2026-11-05 杭州采集实测：触发后重试即可落到下一秒窗口）；
 * 4xx 业务错误（如 key 无效）与配额类错误（10003 日配额耗尽）立即失败，不重试。
 * 第 n 次重试等待 base * 2^(n-1) + [0, base) 毫秒抖动，避免多实例同步重试。</p>
 *
 * <p>失败语义：统一抛 {@link ExternalApiException}（码段 4xxxxx / 高德 400001），
 * retryable 标记供上层降级决策（如通勤矩阵单项降级，设计说明书 6.2 节）。</p>
 */
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    /** 供应商标识（ExternalApiException.supplier，日志与降级展示用） */
    public static final String SUPPLIER = "高德";

    private final RestClient restClient;
    private final AmapProperties properties;
    private final TokenBucketRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmapClient(RestClient restClient, AmapProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        this.rateLimiter = new TokenBucketRateLimiter(properties.getQps());
        if (!StringUtils.hasText(properties.getApiKey())) {
            // 与 LLM 配置同款 fail-fast 约定：密钥缺失启动即失败，避免运行期才暴露
            throw new IllegalStateException(
                    "高德 API Key 未配置：请设置环境变量 AMAP_API_KEY（禁止硬编码/入库，AGENTS.md 第 5 节）");
        }
    }

    /**
     * 发起 GET 请求并返回响应 JSON。
     *
     * @param path   高德 API 路径，如 "/v3/place/text"
     * @param params 业务参数（不含 key，由客户端统一注入）
     * @return 高德响应体（整个 JSON，含 status/infocode，业务侧按需取用 data 字段）
     * @throws ExternalApiException 可重试错误重试耗尽后抛出，或遇到不可重试错误立即抛出
     */
    public JsonNode get(String path, Map<String, String> params) {
        Map<String, String> allParams = new LinkedHashMap<>(params);
        allParams.put("key", properties.getApiKey());
        allParams.put("output", "JSON");

        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString(properties.getBaseUrl() + path);
        allParams.forEach(uriBuilder::queryParam);
        // build().encode()：业务参数（如中文关键词）统一 UTF-8 百分号编码
        URI uri = uriBuilder.build().encode().toUri();

        int maxAttempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; ; attempt++) {
            // 限流：先取令牌再发请求，保证任何时刻不超 QPS
            double waitMs = rateLimiter.acquire();
            if (waitMs > 0) {
                log.debug("高德 QPS 限流等待 {} ms", String.format("%.1f", waitMs));
            }
            try {
                String body = restClient.get()
                        .uri(uri)
                        .retrieve()
                        .body(String.class);
                JsonNode root = checkBusinessStatus(path, body, attempt, maxAttempts);
                if (root == null) {
                    continue; // 业务限流已退避，进入下一轮重试
                }
                return root;
            } catch (RestClientResponseException e) {
                if (isRetryableHttpStatus(e.getStatusCode())) {
                    if (attempt < maxAttempts) {
                        backoff(attempt);
                        continue;
                    }
                    // 可重试错误耗尽次数：仍标记 retryable=true，供上层降级决策（如通勤矩阵单项降级）
                    throw new ExternalApiException(ResultCode.EXTERNAL_AMAP, SUPPLIER, true);
                }
                // 4xx 业务错误（key 无效、参数非法等）不可重试
                throw new ExternalApiException(ResultCode.EXTERNAL_AMAP.getCode(),
                        "高德服务调用失败(" + e.getStatusCode().value() + ")", SUPPLIER, false);
            } catch (org.springframework.web.client.ResourceAccessException e) {
                // 超时 / IO 异常：可重试
                if (attempt < maxAttempts) {
                    log.warn("高德请求网络异常（第 {}/{} 次），准备重试: {}", attempt, maxAttempts, e.getMessage());
                    backoff(attempt);
                    continue;
                }
                throw new ExternalApiException(ResultCode.EXTERNAL_AMAP, SUPPLIER, true);
            }
        }
    }

    /** 高德业务级限流 infocode：触发后按指数退避重试（下一秒窗口即恢复，2026-11-05 实测） */
    private static final Set<String> RETRYABLE_BUSINESS_CODES = Set.of("10021", "10004");

    /**
     * 校验高德业务状态字段：status=0 视为业务失败。
     * 限流类 infocode（10021/10004）按指数退避重试；其余业务失败（key 无效、日配额耗尽等）不可重试，直接抛出。
     */
    private JsonNode checkBusinessStatus(String path, String body, int attempt, int maxAttempts) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            // 响应非法 JSON：不可重试
            log.error("高德响应解析失败: path={}, body={}", path, body, e);
            throw new ExternalApiException(ResultCode.EXTERNAL_AMAP, SUPPLIER, false);
        }
        JsonNode status = root.get("status");
        if (status != null && "0".equals(status.asText())) {
            String infocode = root.path("infocode").asText();
            if (RETRYABLE_BUSINESS_CODES.contains(infocode) && attempt < maxAttempts) {
                log.warn("高德业务限流（{}），第 {}/{} 次重试", infocode, attempt, maxAttempts);
                backoff(attempt);
                return null; // 由调用方 continue 进入下一轮重试
            }
            String info = root.path("info").asText("") + "/" + infocode;
            log.error("高德业务调用失败: path={}, info={}", path, info);
            throw new ExternalApiException(ResultCode.EXTERNAL_AMAP.getCode(),
                    "高德服务返回业务错误(" + info + ")", SUPPLIER,
                    RETRYABLE_BUSINESS_CODES.contains(infocode));
        }
        return root;
    }

    private boolean isRetryableHttpStatus(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    /** 指数退避：base * 2^(attempt-1) + [0, base) 抖动 */
    private void backoff(int attempt) {
        long base = properties.getRetryBaseDelayMs();
        long delay = base * (1L << (attempt - 1))
                + ThreadLocalRandom.current().nextLong(base);
        log.warn("高德请求可重试错误，第 {} 次重试，退避 {} ms", attempt, delay);
        LockSupportSleep.sleep(delay);
    }

    /** 与 LockSupport.parkNanos 等价的毫秒级休眠（退避等待，不持锁） */
    private static final class LockSupportSleep {
        static void sleep(long millis) {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
            }
        }
    }

    /**
     * 构建带超时控制的 RestClient（连接/读取超时来自配置）。
     *
     * <p>用 {@link JdkClientHttpRequestFactory}（JDK HttpClient）而非 Simple 工厂：后者每次请求
     * 新建 TCP 连接且 IPv6/连接建立抖动大（2026-11-05 杭州采集实测大量 Connect timed out），
     * JDK HttpClient 自带连接池与 Happy Eyeballs，同机实测稳定。
     * 连接超时是 JDK HttpClient 的客户端级属性，在构造 HttpClient 时设置。</p>
     */
    public static RestClient buildRestClient(AmapProperties properties) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
