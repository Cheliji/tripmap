package com.tripmap.tool.commute.dto;

import com.tripmap.tool.commute.CommuteMode;
import com.tripmap.tool.spi.ToolInput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通勤矩阵工具输入（任务 2.4，设计说明书 §9：输入 POI ID 列表、出行方式）。
 *
 * <p>坐标由工具内部凭 ID 批量查库解析（POI 坐标即高德 GCJ-02 原始值，
 * amap-route-api.md §1.3），编排节点只装配 ID 与出行方式。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommuteMatrixInput implements ToolInput {

    /** POI ID 列表（一个簇内的点；≥2，顺序即输出矩阵行列顺序） */
    private List<Long> poiIds;

    /** 出行方式：公交 / 驾车（MVP 市内通勤两种） */
    private CommuteMode mode;
}
