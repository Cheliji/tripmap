package com.tripmap.tool.cluster.dto;

import com.tripmap.tool.poi.PoiCandidate;
import com.tripmap.tool.spi.ToolOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 空间聚类工具输出（任务 2.5）。
 *
 * <p>诊断字段（iterations/balanced/movedPoints）对齐 {@code CommuteMatrixOutput}
 * 的风格：供评测节点（3.8）与日志核对聚类收敛质量，不影响消费方主流程。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterOutput implements ToolOutput {

    /** 按日排序的簇列表（daySeq 1..k 连续） */
    private List<DayCluster> clusters;

    /** 行程天数（k） */
    private int days;

    /** 实际生效的随机种子（可复现性证据：同输入同输出） */
    private long seed;

    /** K-Means 实际迭代轮数 */
    private int iterations;

    /** 平衡校正后是否达标（簇间大小差 ≤ 阈值）；false 时仍返回当前最优结果 */
    private boolean balanced;

    /** 平衡校正阶段移动的景点数 */
    private int movedPoints;

    /** 必去点近邻归并后的锚组数（0 = 无必去点，纯 K-Means） */
    private int anchorGroupCount;

    /**
     * 被剥离的边缘点（候选中的候选）：离群远点不参与聚类，由编排层/LLM 决策
     * 作为替补池使用（2026-11-20 真实数据验证引入：K-Means 初始化会被离群点绑架，
     * 导致市中心 20+ 点挤一天；剥离后密集区正常分簇）。
     */
    private List<PoiCandidate> excluded;

    /**
     * 边缘点剥离原因（id → 人类可读说明，如 "距最近 2 邻居均值 35.2km（阈值 15km）"），
     * 供编排层/LLM 结合上下文决策（如必去点虽远仍需单独安排）。
     */
    private Map<Long, String> excludedReason;
}
