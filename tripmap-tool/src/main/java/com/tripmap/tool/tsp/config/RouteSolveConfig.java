package com.tripmap.tool.tsp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 日内路径求解装配（任务 2.6）。
 *
 * <p>tripmap-tool 自身不提供启动类，由 tripmap-server 扫描装配
 * （启动类 scanBasePackages 覆盖 com.tripmap，同 ClusterConfig 模式）；
 * 工具本体为纯本地算法（全排列精确枚举），零外部依赖，此处仅注册配置属性。</p>
 */
@Configuration
@EnableConfigurationProperties(RouteSolveProperties.class)
public class RouteSolveConfig {

    private static final Logger log = LoggerFactory.getLogger(RouteSolveConfig.class);

    public RouteSolveConfig(RouteSolveProperties properties) {
        log.info("日内路径求解装配（纯 Java 精确枚举）：默认出发={}, 午餐禁游=[{},{}]（首末相等=禁用）, 默认游玩时长={}min, 枚举护栏≤{}点",
                properties.getDefaultStartTime(), properties.getDefaultLunchStart(),
                properties.getDefaultLunchEnd(), properties.getDefaultVisitMinutes(),
                properties.getMaxPoints());
    }
}
