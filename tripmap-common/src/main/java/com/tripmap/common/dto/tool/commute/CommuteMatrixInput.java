package com.tripmap.common.dto.tool.commute;

import com.tripmap.common.dto.tool.ToolInput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通勤矩阵工具输入（任务 2.4，设计说明书 §9）。
 *
 * <p>由规划计算链节点按"同簇两两"装配；矩阵元素经 Redis 缓存
 * （§8.3，TTL 7 天，兼作高德 QPS 限流保护）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "通勤矩阵工具输入")
public class CommuteMatrixInput implements ToolInput {

    @Schema(description = "同簇 POI ID 列表（≥2，两两组合）")
    private List<Long> poiIds;

    @Schema(description = "出行方式")
    private TravelMode mode;
}
