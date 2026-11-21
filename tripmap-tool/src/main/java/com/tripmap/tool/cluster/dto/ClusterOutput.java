package com.tripmap.tool.cluster.dto;

import com.tripmap.tool.poi.PoiCandidate;
import com.tripmap.tool.spi.ToolOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 空间聚类工具输出（任务 2.5，v1.4 重做为 CP-SAT 约束划分）。
 *
 * <p>v1.4 变更：求解由 K-Means 改为 CP-SAT 硬约束划分（每日 1~7 点、簇直径、
 * 必去锚定），故移除 K-Means 相关诊断字段（seed/iterations/balanced/movedPoints）
 * 与边缘点剥离字段（excluded/excludedReason）——单点远郊日在新模型下为合法解。
 * 确定性由"固定求解参数 + 单 worker"保证，无需种子。</p>
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

    /** 必去点近邻归并后的锚组数（0 = 无必去点，纯约束划分） */
    private int anchorGroupCount;
}
