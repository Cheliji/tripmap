package com.tripmap.common.dto.tool.cluster;

import com.tripmap.common.dto.tool.ToolInput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 空间聚类工具输入（任务 2.5，设计说明书 §6.2）。
 *
 * <p>由候选筛选节点装配：top-N 候选投影为 {@link PoiPoint}，
 * k = 行程天数（背景 Schema），{@code anchorPoiIds} 为必去点锚定
 * （先各占一天或按用户意愿集中，§6.2 锚定）；
 * {@code seed} 固定随机种子保证结果可复现（2.5.2）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "空间聚类工具输入")
public class ClusterInput implements ToolInput {

    @Schema(description = "候选 POI 空间点（top-N 个）")
    private List<PoiPoint> points;

    @Schema(description = "目标簇数 k = 行程天数", example = "3")
    private Integer k;

    @Schema(description = "必去点锚定 ID（先各占一簇；可为空）")
    private List<Long> anchorPoiIds;

    @Schema(description = "随机种子（可复现性，2.5.2）", example = "42")
    private Long seed;
}
