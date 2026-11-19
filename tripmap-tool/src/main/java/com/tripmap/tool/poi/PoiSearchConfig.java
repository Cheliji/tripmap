package com.tripmap.tool.poi;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * POI 检索工具装配（任务 2.2）。
 *
 * <p>tripmap-tool 自身不提供启动类，由 tripmap-server 扫描装配
 * （启动类 scanBasePackages 覆盖 com.tripmap，同 AmapClientConfig 模式）。</p>
 */
@Configuration
@EnableConfigurationProperties(PoiSearchProperties.class)
public class PoiSearchConfig {

    @Bean
    public PoiRecallScorer poiRecallScorer(PoiSearchProperties properties) {
        return new PoiRecallScorer(properties);
    }
}
