package com.tripmap.tool.tsp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 日内路径求解单站明细（任务 2.6 输出项，与 {@link RouteSolveOutput#getOrderedPoiIds()} 对齐）。
 *
 * <p>内部计算全程按秒（通勤矩阵原生单位为秒），到/离时刻输出为"当日分钟数"（向下取整，
 * 与 open_hours 的 HH:mm 口径一致，供 3.6.2 时间线组装与 3.8.1 硬校验 #3 直接消费）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteStop {

    /** POI ID */
    private Long poiId;

    /** 到达时刻（分钟，自当日 00:00 起；首站 = 出发时刻经开放等待/午餐顺延后的实际开始） */
    private int arriveMinuteOfDay;

    /** 离开时刻（分钟，= 到达 + 游玩时长） */
    private int departMinuteOfDay;

    /** 本点游玩时长（分钟，= suggest_duration 或默认值） */
    private int visitMinutes;

    /** 本点等待秒数（开放等待 + 午餐顺延等待；首站含"出发→开放"等待） */
    private long waitSeconds;

    /** 从上一站到本点的通勤秒数（首站为 0，双开放路径不设固定起点） */
    private long commuteFromPrevSeconds;

    /** 是否因 suggest_duration 缺失套用了默认时长（下游攻略节点据此不对该点生成时长表述） */
    private boolean defaultDurationApplied;

    /** 是否因 open_hours 未知套用了 24h 开放假设 */
    private boolean defaultOpenHoursApplied;
}
