package com.tripmap.tool.amap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 高德客户端装配（任务 1.1.2）
 *
 * <p>tripmap-tool 自身不提供启动类，由 tripmap-server 扫描装配
 * （启动类 scanBasePackages 覆盖 com.tripmap）。</p>
 */
@Configuration
@EnableConfigurationProperties(AmapProperties.class)
public class AmapClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AmapClientConfig.class);

    @Bean
    public RestClient amapRestClient(AmapProperties properties) {
        return AmapClient.buildRestClient(properties);
    }

    @Bean
    public AmapClient amapClient(@Qualifier("amapRestClient") RestClient amapRestClient, AmapProperties properties) {
        log.info("高德客户端装配：baseUrl={}, qps={}, 超时 {}ms/{}ms, 重试 {} 次(基准 {}ms)",
                properties.getBaseUrl(), properties.getQps(),
                properties.getConnectTimeoutMs(), properties.getReadTimeoutMs(),
                properties.getMaxRetries(), properties.getRetryBaseDelayMs());
        return new AmapClient(amapRestClient, properties);
    }
}
