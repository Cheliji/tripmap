package com.tripmap.tool.tsp.dto;

import com.tripmap.tool.spi.ToolOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日内路径求解工具输出（任务 2.6，设计说明书 §6.3 双开放最短路径）。
 *
 * <p>起点与终点均由求解器自由选择，目标为<b>总通勤时间最小</b>（等待时间仅作
 * 同通勤下的次级裁决，不参与目标）；各站明细供 3.6.2 时间线组装 / 3.8.1
 * 硬校验消费。全部排列不可行时工具抛 {@code BizException(300002)}，不返回空解。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteSolveOutput implements ToolOutput {

    /** 最优访问顺序（POI ID，与输入去重后顺序同源；起终点自由） */
    private List<Long> orderedPoiIds;

    /** 总通勤时间（秒，目标值） */
    private long totalCommuteSeconds;

    /** 总等待时间（秒，开放等待 + 午餐顺延等待） */
    private long totalWaitSeconds;

    /** 当日总耗时（分钟，末站离开 − 出发时刻，向下取整） */
    private int totalElapsedMinutes;

    /** 每站明细（与 orderedPoiIds 一一对应） */
    private List<RouteStop> stops;
}
