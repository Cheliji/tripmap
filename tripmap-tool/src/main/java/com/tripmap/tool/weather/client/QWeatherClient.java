package com.tripmap.tool.weather.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.util.TokenBucketRateLimiter;
import com.tripmap.tool.weather.config.QWeatherProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPInputStream;

/**
 * 和风天气 HTTP 客户端（任务 2.3.1）。
 *
 * <p>统一承担：API Host 拼装、密钥注入（X-QW-Api-Key 请求头）、QPM 限流（令牌桶）、
 * 超时控制（连接/读取）、gzip 解压（和风所有响应 gzip 压缩，接口文档 §2.3）、
 * 失败重试（指数退避 + 抖动）。业务侧只关心路径与参数，逐日/实况等在此之上封装。</p>
 *
 * <p>重试策略（指数退避）：仅对可重试错误重试——网络超时/IO 异常、HTTP 429（限流）、
 * 5xx（服务端故障）；4xx（参数非法/认证失败/Host 错误/额度不足等）立即失败不重试
 * （接口文档 §10：4xx 重试只会触发风控）。失败统一抛
 * {@link ExternalApiException}（码段 4xxxxx / 天气 400002），retryable 供上层降级决策。</p>
 */
public class QWeatherClient {

    private static final Logger log = LoggerFactory.getLogger(QWeatherClient.class);

    /** 供应商标识（ExternalApiException.supplier，日志与降级展示用） */
    public static final String SUPPLIER = "和风";

    private final RestClient restClient;
    private final QWeatherProperties properties;
    private final TokenBucketRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QWeatherClient(RestClient restClient, QWeatherProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        this.rateLimiter = new TokenBucketRateLimiter(properties.getQpm());
        // 密钥/Host 缺失启动即失败，避免运行期才暴露（AGENTS.md 第 5 节）
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                    "和风 API Key 未配置：请设置环境变量 QWEATHER_API_KEY（禁止硬编码/入库，AGENTS.md 第 5 节）");
        }
        if (!StringUtils.hasText(properties.getApiHost())) {
            throw new IllegalStateException(
                    "和风 API Host 未配置：请设置环境变量 QWEATHER_API_HOST（控制台-设置 查看，禁止用公共域名）");
        }
    }

    /**
     * 逐日预报（任务 2.3.2）：{@code GET /weather/v1/daily/{lat}/{lon}}。
     *
     * @param lat  纬度（GCJ-02，最多两位小数）
     * @param lon  经度（GCJ-02，最多两位小数）
     * @param days 预报天数 1～10（默认 7，显式传值）
     * @return 响应体（含 days 数组），归一化由 {@link com.tripmap.tool.weather.util.WeatherNormalizer} 负责
     */
    public JsonNode getDailyForecast(double lat, double lon, int days) {
        String path = "/weather/v1/daily/" + format(lat) + "/" + format(lon);
        Map<String, String> params = Map.of(
                "days", String.valueOf(days),
                "localTime", "true",
                "lang", "zh");
        return get(path, params);
    }

    /**
     * 发起 GET 请求并返回响应 JSON。
     *
     * @param path   和风 API 路径（坐标已拼入路径）
     * @param params 查询参数（密钥走请求头，不在此注入）
     * @throws ExternalApiException 可重试错误重试耗尽后抛出，或不可重试错误立即抛出
     */
    public JsonNode get(String path, Map<String, String> params) {
        Map<String, String> allParams = new LinkedHashMap<>(params);
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString(properties.getApiHost() + path);
        allParams.forEach(uriBuilder::queryParam);
        URI uri = uriBuilder.build().encode().toUri();

        int maxAttempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; ; attempt++) {
            double waitMs = rateLimiter.acquire();
            if (waitMs > 0) {
                log.debug("和风 QPM 限流等待 {} ms", String.format("%.1f", waitMs));
            }
            try {
                HttpResult result = restClient.get()
                        .uri(uri)
                        .header("X-QW-Api-Key", properties.getApiKey())
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                        .exchange((request, response) -> {
                            byte[] raw = response.getBody().readAllBytes();
                            return new HttpResult(response.getStatusCode().value(),
                                    decodeIfNeeded(response.getHeaders(), raw));
                        });
                if (result.status() == 200) {
                    return objectMapper.readTree(result.body());
                }
                if (isRetryableStatus(result.status())) {
                    if (attempt < maxAttempts) {
                        backoff(attempt);
                        continue;
                    }
                    throw new ExternalApiException(ResultCode.EXTERNAL_WEATHER, SUPPLIER, true);
                }
                // 4xx：参数非法/认证失败/Host 错误/额度不足，不可重试
                throw new ExternalApiException(ResultCode.EXTERNAL_WEATHER.getCode(),
                        "和风服务调用失败(HTTP " + result.status() + ")", SUPPLIER, false);
            } catch (ResourceAccessException e) {
                // 超时 / IO 异常：可重试
                if (attempt < maxAttempts) {
                    log.warn("和风请求网络异常（第 {}/{} 次），准备重试: {}", attempt, maxAttempts, e.getMessage());
                    backoff(attempt);
                    continue;
                }
                throw new ExternalApiException(ResultCode.EXTERNAL_WEATHER, SUPPLIER, true);
            } catch (IOException e) {
                // 响应非法 JSON / gzip 解压失败：不可重试
                log.error("和风响应解析失败: path={}", path, e);
                throw new ExternalApiException(ResultCode.EXTERNAL_WEATHER.getCode(),
                        "和风响应解析失败", SUPPLIER, false);
            }
        }
    }

    /** HTTP 状态 + 已解压响应体（exchange 回调内捕获状态码） */
    private record HttpResult(int status, byte[] body) {
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /** 指数退避：base * 2^(attempt-1) + [0, base) 抖动 */
    private void backoff(int attempt) {
        long base = properties.getRetryBaseDelayMs();
        long delay = base * (1L << (attempt - 1))
                + ThreadLocalRandom.current().nextLong(base);
        log.warn("和风请求可重试错误，第 {} 次重试，退避 {} ms", attempt, delay);
        sleep(delay);
    }

    /** 响应 gzip 解压：和风所有响应 gzip 压缩（接口文档 §2.3） */
    private byte[] decodeIfNeeded(HttpHeaders headers, byte[] raw) throws IOException {
        String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                gz.transferTo(out);
                return out.toByteArray();
            }
        }
        return raw;
    }

    /** 坐标格式化为最多两位小数（接口文档 §2.3） */
    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static void sleep(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    /**
     * 构建带超时控制的 RestClient（连接/读取超时来自配置）。
     *
     * <p>复用 {@link JdkClientHttpRequestFactory}（JDK HttpClient）：自带连接池与
     * Happy Eyeballs，避免 Simple 工厂每次新建 TCP 连接（同 AmapClient 2026-11 实测结论）。</p>
     */
    public static RestClient buildRestClient(QWeatherProperties properties) {
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
