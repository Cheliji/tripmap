package com.tripmap.tool.search.config;

import com.tripmap.tool.deepseek.DeepSeekProperties;
import com.tripmap.tool.deepseek.DeepSeekSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 联网搜索装配（任务 2.7.1）。
 *
 * <p>DeepSeek 属性复用 {@link DeepSeekProperties}（与 tripmap-poi 功能二
 * 同一载体，tripmap-poi功能设计说明书 §4.8）；该类刻意保持零 Spring 注解
 * （离线脚本直接 new），运行时装配通过 {@code @Bean + @ConfigurationProperties}
 * 方法绑定完成，密钥缺失时客户端构造即失败（fail-fast，与 AmapClient 同款约定）。</p>
 *
 * <p>tripmap-tool 自身不提供启动类，由 tripmap-server 扫描装配
 * （启动类 scanBasePackages 覆盖 com.tripmap，同 QWeatherClientConfig 模式）。</p>
 */
@Configuration
public class WebSearchConfig {

    private static final Logger log = LoggerFactory.getLogger(WebSearchConfig.class);

    @Bean
    @ConfigurationProperties("tripmap.deepseek")
    public DeepSeekProperties deepSeekProperties() {
        return new DeepSeekProperties();
    }

    @Bean
    public RestClient deepSeekSearchRestClient(DeepSeekProperties deepSeekProperties) {
        return DeepSeekSearchClient.buildRestClient(deepSeekProperties);
    }

    @Bean
    public DeepSeekSearchClient deepSeekSearchClient(
            @Qualifier("deepSeekSearchRestClient") RestClient deepSeekSearchRestClient,
            DeepSeekProperties deepSeekProperties) {
        log.info("DeepSeek 联网搜索装配：baseUrl={}, count={}, 最小间隔 {}ms, 超时 {}ms/{}ms, 重试 {} 次(基准 {}ms)",
                deepSeekProperties.getBaseUrl(), deepSeekProperties.getSearchCount(),
                deepSeekProperties.getSearchMinIntervalMs(),
                deepSeekProperties.getConnectTimeoutMs(), deepSeekProperties.getSearchReadTimeoutMs(),
                deepSeekProperties.getMaxRetries(), deepSeekProperties.getRetryBaseDelayMs());
        return new DeepSeekSearchClient(deepSeekSearchRestClient, deepSeekProperties);
    }
}
