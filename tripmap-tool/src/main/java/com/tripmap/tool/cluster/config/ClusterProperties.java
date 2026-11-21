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

    /** CP-SAT 确定性时间上限（秒，基于求解器内部确定性计数器，与 wall-clock 无关）：
     * 固定此值 + 单 worker + 固定随机种子 ⇒ 同输入同输出（即使截断返回近似解也确定）。
     * k=5 约 2.5 单位到最优，取 8 留裕量；k≥8 高天数在 8 单位内出高质量可行解 */
    private double maxSolveDeterministicTime = 8.0;

    /** CP-SAT wall-clock 硬上限（秒）：仅防极端数据（n=40 高天数）下确定性时间对应
     * 的 wall-clock 过久；正常情况由 maxSolveDeterministicTime 先行截断 */
    private double maxSolveWallClockSeconds = 30.0;
}
