package com.tripmap.common.dto.tool.cluster;

import com.tripmap.common.dto.tool.ToolOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 空间聚类工具输出（任务 2.5）。
 *
 * <p>{@code dayClusters} 按"天"排好序（簇→天排序按簇质心邻近关系，2.5.5，
 * 仅降低换区成本，不构成日内路径约束）；列表下标即天序（0 起），
 * 每簇的 POI 数由锚定与平衡校正（簇间差 >2 移交最远点）产出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "空间聚类工具输出")
public class ClusterOutput implements ToolOutput {

    @Schema(description = "按天排序的簇列表（下标=天序，元素=当日 POI ID 集合）")
    private List<List<Long>> dayClusters;

    @Schema(description = "实际使用的随机种子（审计可复现性）")
    private Long seedUsed;
}
