package com.tripmap.common.dto.tool.route;

import com.tripmap.common.dto.tool.ToolInput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 日内路径求解工具输入（任务 2.6，设计说明书 §6.3 双开放最短路径）。
 *
 * <p>由规划计算链节点装配（3.6.1）：同簇 POI + 该簇通勤矩阵 + 按行程日期
 * 推算星期后解析出的当日开放时段。每日 3～7 点规模，全排列 n!/2 去对称
 * 精确枚举，数学最优、零 native 依赖（v1.1 决策）。</p>
 *
 * <p>{@code openWindows} 三态语义（§8.1）：</p>
 * <ul>
 *   <li>key 缺失或 value 为 null —— 开放时间<b>未知</b>，不参与时间窗硬约束；</li>
 *   <li>value = 空列表 —— <b>确定闭馆</b>，当日任何排列均不可行，立即报错（回到候选筛选启用替补）；</li>
 *   <li>value = 时段数组 —— 确定开放，按序累计到访时刻剪枝。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日内路径求解工具输入")
public class RouteSolveInput implements ToolInput {

    @Schema(description = "当日簇内 POI ID 列表（3～7 个）")
    private List<Long> poiIds;

    @Schema(description = "簇内两两通勤时间矩阵（key 规范同 CommuteMatrixOutput.keyOf，单位分钟；矩阵由上游通勤工具产出）")
    private Map<String, Integer> matrixMinutes;

    @Schema(description = "各 POI 建议游玩时长（分钟），离开时刻=到访+游玩，用于闭馆剪枝")
    private Map<Long, Integer> visitDurationMinutes;

    @Schema(description = "当日开放时段（按星期解析后的三态结果，见类注释）")
    private Map<Long, List<TimeWindow>> openWindows;

    @Schema(description = "当日最早出发时刻（分钟，如 9:00=540），此前不排程", example = "540")
    private Integer dayStartMinute;
}
