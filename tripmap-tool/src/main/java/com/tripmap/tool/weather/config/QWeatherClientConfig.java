package com.tripmap.tool.weather.config;

import com.tripmap.tool.weather.client.QWeatherClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 和风客户端装配（任务 2.3.1）。
 *
 * <p>tripmap-tool 自身不提供启动类，由 tripmap-server 扫描装配
 * （启动类 scanBasePackages 覆盖 com.tripmap，同 AmapClientConfig 模式）。</p>
 */
@Configuration
@EnableConfigurationProperties(QWeatherProperties.class)
public class QWeatherClientConfig {

    private static final Logger log = LoggerFactory.getLogger(QWeatherClientConfig.class);

    @Bean
    public RestClient qweatherRestClient(QWeatherProperties properties) {
        return QWeatherClient.buildRestClient(properties);
    }

    @Bean
    public QWeatherClient qweatherClient(RestClient qweatherRestClient, QWeatherProperties properties) {
        log.info("和风客户端装配：apiHost={}, qpm={}, 超时 {}ms/{}ms, 重试 {} 次(基准 {}ms), "
                        + "预报窗口 {} 天, 降级阈值 {} 天",
                properties.getApiHost(), properties.getQpm(),
                properties.getConnectTimeoutMs(), properties.getReadTimeoutMs(),
                properties.getMaxRetries(), properties.getRetryBaseDelayMs(),
                properties.getForecastMaxDays(), properties.getDegradeThreshold());
        return new QWeatherClient(qweatherRestClient, properties);
    }
}
