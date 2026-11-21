package com.tripmap.tool.cluster.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 空间聚类工具配置（任务 2.5，设计说明书 v1.4 §6.2，CP-SAT 约束划分）。
 *
 * <p>每日点数上下界、簇直径、必去归并距离、求解时限全部配置化（调优只改
 * application.yml）。v1.4 移除 K-Means 相关配置（种子/迭代/平衡/边缘点剥离）。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tripmap.cluster")
public class ClusterProperties {

    /** 每日最少景点数（硬约束下界，设计说明书 v1.4 §6.2，默认 1） */
    private int minPoisPerDay = 1;

    /** 每日最多景点数（硬约束上界，默认 7） */
    private int maxPoisPerDay = 7;

    /**
     * 簇直径硬约束（米）：同一天任意两点 haversine 直线距离不得超过此值
     * （配置化，2026-11-21 讨论定案）。杭州主城区尺度（西湖→钱塘江→萧山→滨江）
     * 约 10~20km，本值取 20km 覆盖主城区一天范围；跨江/跨山（直线近、通勤远）
     * 不依赖此阈值，由通勤禁配回切环（forbiddenPairs）兜住；远郊稀疏孤点
     * （最近邻 > 阈值）由孤点豁免并入最近簇。
     */
    private double maxClusterDiameterMeters = 20000.0;

    /**
     * 必去点近邻归并距离（米）：必去点两两距离不超过此值的归为一组、
     * 安排在同一天（传递闭包：A 近 B、B 近 C ⇒ 同组）。
     * 宁可偏小——只把"同一区域"的点合并，杭州尺度下西湖环线内成立、
     * 灵隐—西溪（5km+）不成立。
     */
    private double anchorMergeDistanceMeters = 1500.0;

    /** CP-SAT 求解时限（秒）：n≤40 规模 k=5 约 3 秒到最优，k≥8 高天数 3 秒内出
     * 高质量可行解（凝聚度目标持续改善，无需证明最优）；上限兜底防异常膨胀 */
    private double maxSolveTimeSeconds = 3.0;
}
