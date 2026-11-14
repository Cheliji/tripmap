package com.tripmap.common.dto.tool.route;

import com.tripmap.common.dto.tool.ToolOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日内路径求解工具输出（任务 2.6）：双开放最短路径访问顺序。
 *
 * <p>起点与终点均由求解器自由选择（起终点 = {@code orderedPoiIds} 首尾，
 * 显式冗余字段 {@code startPoiId}/{@code endPoiId} 便于节点直读与时间线组装）；
 * {@code totalWaitMinutes} 为开放时间前等待合计（设计说明书 §6.3 等待计入耗时），
 * 供硬校验#4 通勤占比与 3.6.2 时间线组装使用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日内路径求解工具输出")
public class RouteSolveOutput implements ToolOutput {

    @Schema(description = "最优访问顺序（首=起点，尾=终点）")
    private List<Long> orderedPoiIds;

    @Schema(description = "起点 POI ID（= orderedPoiIds 首元素）")
    private Long startPoiId;

    @Schema(description = "终点 POI ID（= orderedPoiIds 尾元素）")
    private Long endPoiId;

    @Schema(description = "总通勤时间（分钟）")
    private Integer totalCommuteMinutes;

    @Schema(description = "开放时间前等待合计（分钟）")
    private Integer totalWaitMinutes;
}
