package com.tripmap.tool.cluster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 空间聚类装配（任务 2.5）。
 *
 * <p>tripmap-tool 自身不提供启动类，由 tripmap-server 扫描装配
 * （启动类 scanBasePackages 覆盖 com.tripmap，同 CommuteMatrixConfig 模式）；
 * 工具本体为纯本地算法、零外部依赖，此处仅注册配置属性。</p>
 */
@Configuration
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterConfig {

    private static final Logger log = LoggerFactory.getLogger(ClusterConfig.class);

    public ClusterConfig(ClusterProperties properties) {
        log.info("空间聚类装配（CP-SAT 约束划分）：每日点数[{},{}], 簇直径≤{}m, 锚点归并距离={}m, 确定性时间上限={}s, wall-clock 兜底={}s",
                properties.getMinPoisPerDay(), properties.getMaxPoisPerDay(),
                properties.getMaxClusterDiameterMeters(), properties.getAnchorMergeDistanceMeters(),
                properties.getMaxSolveDeterministicTime(), properties.getMaxSolveWallClockSeconds());
    }
}
