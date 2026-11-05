package com.tripmap.tool.amap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德开放平台客户端配置（任务 1.1.2）
 *
 * <p>api-key 一律环境变量注入（AGENTS.md 第 5 节），见 application-{env}.yml；
 * 其余调优项见 application.yml。密钥缺失时启动即失败，避免运行期才暴露。</p>
 */
@ConfigurationProperties(prefix = "tripmap.amap")
public class AmapProperties {

    /** 高德 Web 服务 API Key（环境变量 AMAP_API_KEY 注入，禁止硬编码/入库） */
    private String apiKey;

    /** 高德开放平台 Web 服务域名 */
    private String baseUrl = "https://restapi.amap.com";

    /** QPS 配额（令牌桶速率，次/秒）。当前高德配额 3 次/秒 */
    private double qps = 3.0;

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 8000;

    /** 最大重试次数（指数退避，不含首次调用）。0 表示不重试 */
    private int maxRetries = 2;

    /** 重试退避基准间隔（毫秒）：第 n 次重试等待 base * 2^(n-1) + 抖动 */
    private long retryBaseDelayMs = 500;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public double getQps() {
        return qps;
    }

    public void setQps(double qps) {
        this.qps = qps;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public void setRetryBaseDelayMs(long retryBaseDelayMs) {
        this.retryBaseDelayMs = retryBaseDelayMs;
    }
}
