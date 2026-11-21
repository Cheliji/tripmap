package com.tripmap.tool.tsp.dto;

import com.tripmap.tool.spi.ToolInput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 日内路径求解工具输入（任务 2.6，设计说明书 §9：输入矩阵、时间窗）。
 *
 * <p>编排节点（3.6）按日装配：{@code poiIds} + {@code durationsSeconds} 直接透传
 * {@code CommuteMatrixOutput}（顺序一致）；open_hours / suggest_duration 由工具
 * 内部凭 ID 查库解析（与 {@code CommuteMatrixTool.loadPois} 同一模式，DB 为事实源）。</p>
 *
 * <p>2026-11 对齐结论：</p>
 * <ul>
 *   <li>{@code startTime}：当日出发锚点，null → 配置默认 09:00；</li>
 *   <li>{@code lunchStart}/{@code lunchEnd}：午餐禁游窗口（游玩区间不得相交），
 *       null → 配置默认 11:30~14:00，首末相等表示禁用；</li>
 *   <li>{@code defaultVisitMinutes}：suggest_duration 缺失时的默认游玩时长，
 *       由 LLM 按用户节奏传入，null → 配置默认 90。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteSolveInput implements ToolInput {

    /** 簇内 POI ID 列表（顺序即通勤矩阵行列顺序，与 CommuteMatrixOutput.poiIds 一致） */
    private List<Long> poiIds;

    /** 通勤耗时矩阵（秒）：[i][j] = i→j，对角线 0，-1 = 缺失（直接吃 CommuteMatrixOutput.durationsSeconds） */
    private long[][] durationsSeconds;

    /** 当日日期（据此推算星期几，取 open_hours 的 mon~sun 对应键） */
    private LocalDate date;

    /** 当日出发时刻（首个景点到达锚点）；null → 配置默认 09:00 */
    private LocalTime startTime;

    /** 午餐禁游开始；null → 配置默认 11:30 */
    private LocalTime lunchStart;

    /** 午餐禁游结束；null → 配置默认 14:00；{@code lunchStart == lunchEnd} 表示禁用午餐约束 */
    private LocalTime lunchEnd;

    /** suggest_duration 缺失时的默认游玩时长（分钟）；null → 配置默认 90 */
    private Integer defaultVisitMinutes;
}
