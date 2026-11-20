package com.tripmap.tool.cluster.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 空间聚类工具配置（任务 2.5，设计说明书 §6.2）。
 *
 * <p>种子、迭代上限、平衡阈值、锚点归并距离全部配置化（调优只改 application.yml）。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tripmap.cluster")
public class ClusterProperties {

    /** K-Means 默认随机种子（输入未指定时使用；保证同输入同输出，TASKS 2.5.2） */
    private long seed = 42L;

    /** K-Means 最大迭代轮数（正常情况数十轮内收敛，上限防死循环） */
    private int maxIterations = 100;

    /** 簇平衡阈值：簇间大小差超过此值时触发"大簇最远点移交小簇"（设计说明书 §6.2） */
    private int balanceThreshold = 2;

    /**
     * 必去点近邻归并距离（米）：必去点两两距离不超过此值的归为一组、
     * 安排在同一天（传递闭包：A 近 B、B 近 C ⇒ 同组）。
     * 宁可偏小——只把"同一区域"的点合并，杭州尺度下西湖环线内成立、
     * 灵隐—西溪（5km+）不成立
     */
    private double anchorMergeDistanceMeters = 1500.0;

    /**
     * 平衡移交最大距离（米）：簇平衡校正时，大簇最远自由点与接收小簇质心的距离
     * 不得超过此值，否则视为非相邻区域拒绝移交（真实数据验证细化，2026-11-19：
     * 防止把市中心点拽进几十公里外的远郊天，违背"一天一个区域"）。
     */
    private double maxBalanceMoveMeters = 15000.0;

    /**
     * 边缘点剥离的 k-NN 邻居数：计算每个点到最近 k 个邻居的平均距离，
     * 作为"孤立程度"度量（k=2 比 k=1 更鲁棒，避免单个近邻误判）。
     */
    private int outlierKnn = 2;

    /**
     * 边缘点剥离的阈值分位数：取所有点 k-NN 距离的该分位数作为动态阈值。
     * 杭州数据天然"市中心密、远郊散"，P90 会自动落在"市区正常间距"与"远郊异常间距"之间，
     * 无需人工调参（真实数据验证，2026-11-20）。
     */
    private double outlierThresholdQuantile = 0.90;

    /**
     * 边缘点剥离的绝对下限（米）：即使分位数阈值更低，k-NN 距离超过此值的点也强制剥离。
     * 防止市中心数据波动时分位数阈值飘低，误把正常市区点剥成边缘点。
     */
    private double outlierMinDistanceMeters = 15000.0;
}
