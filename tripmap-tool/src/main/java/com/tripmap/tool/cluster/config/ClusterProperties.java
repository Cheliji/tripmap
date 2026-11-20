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
}
