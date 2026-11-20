package com.tripmap.tool.commute.config;

import com.tripmap.tool.amap.AmapClient;
import com.tripmap.tool.commute.client.AmapRouteClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通勤矩阵装配（任务 2.4）。
 *
 * <p>tripmap-tool 自身不提供启动类，由 tripmap-server 扫描装配
 * （启动类 scanBasePackages 覆盖 com.tripmap，同 AmapClientConfig 模式）。</p>
 */
@Configuration
@EnableConfigurationProperties(CommuteMatrixProperties.class)
public class CommuteMatrixConfig {

    private static final Logger log = LoggerFactory.getLogger(CommuteMatrixConfig.class);

    @Bean
    public AmapRouteClient amapRouteClient(AmapClient amapClient, CommuteMatrixProperties properties) {
        log.info("通勤矩阵装配：驾车策略={}, 公交策略={}, 公交方案条数={}, 缺失率阈值={}, "
                        + "并行度={}, 缓存 TTL {} 天, citycodes={}",
                properties.getDrivingStrategy(), properties.getTransitStrategy(),
                properties.getAlternativeRoute(), properties.getMissingRatioThreshold(),
                properties.getMaxConcurrency(), properties.getCacheTtlDays(),
                properties.getCityCodes());
        return new AmapRouteClient(amapClient, properties);
    }
}
