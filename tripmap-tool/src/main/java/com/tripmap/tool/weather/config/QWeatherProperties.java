package com.tripmap.tool.weather.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 和风天气客户端配置（任务 2.3.1）。
 *
 * <p>{@code api-host} / {@code api-key} 一律环境变量注入（AGENTS.md 第 5 节），
 * 缺省留空、装配时启动即失败（与 {@code AmapClient} 同款 fail-fast）；
 * 其余调优项与坐标走 application.yml 公共配置。</p>
 *
 * <p>Lombok：本类含密钥字段，用 {@code @Getter}/{@code @Setter} 而不用 {@code @Data}，
 * 避免默认 toString 打印 apiKey（AGENTS.md 第 6 节）。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tripmap.qweather")
public class QWeatherProperties {

    /** 和风独立 API Host（每人唯一，含 https:// 前缀，如 https://h2a9cf3mhs.xy.qweatherapi.com） */
    private String apiHost;

    /** 和风 API KEY（请求头 X-QW-Api-Key 注入；MVP 用 API KEY，JWT 预留开关） */
    private String apiKey;

    /** 默认城市（MVP 仅杭州；工具层防御性白名单校验复用 ResultCode.CITY_NOT_SUPPORTED） */
    private String defaultCity = "杭州";

    /** 默认城市纬度（GCJ-02，最多两位小数；MVP 杭州城市中心，省去 geo 解析请求） */
    private double cityCoordinateLat = 30.25;

    /** 默认城市经度（GCJ-02，最多两位小数） */
    private double cityCoordinateLon = 120.15;

    /** 预报窗口上限（天）：v1 daily 最大 10 天，offset ∈ [0, forecastMaxDays) 可真实预报 */
    private int forecastMaxDays = 10;

    /** 降级阈值（天）：offset >= 此值明确降级。方案 A 下与 forecastMaxDays 相等；
     *  若将来启用 v7 15d 填补 11～15 天夹层（方案 B），调大此值即可，不改代码 */
    private int degradeThreshold = 10;

    /** QPM 限流（令牌桶速率，次/秒）。和风按量计费 QPM 3000/分钟，此处默认 50 次/秒留足余量 */
    private double qpm = 50;

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 10000;

    /** 最大重试次数（指数退避，不含首次调用） */
    private int maxRetries = 2;

    /** 重试退避基准间隔（毫秒）：第 n 次重试等待 base * 2^(n-1) + 抖动 */
    private long retryBaseDelayMs = 500;

    /** Redis 缓存 TTL（分钟），TASKS 2.3.4 要求 30～60 分钟 */
    private int cacheTtlMinutes = 30;
}
