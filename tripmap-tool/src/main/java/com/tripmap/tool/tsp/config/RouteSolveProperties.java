package com.tripmap.tool.tsp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * 日内路径求解工具（TSP）配置（任务 2.6，设计说明书 §6.3）。
 *
 * <p>出发时刻、午餐禁游窗口、默认游玩时长、枚举护栏全部配置化
 * （调优只改 application.yml）。input 显式传值时优先于本默认值——
 * 2026-11 对齐结论：默认游玩时长 / 午餐时段由编排层（LLM 按用户背景）
 * 传入 input，未传时回落本配置。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tripmap.route-solve")
public class RouteSolveProperties {

    /** 当日出发时刻（首个景点的到达锚点）；input.startTime 为 null 时取此默认 */
    private LocalTime defaultStartTime = LocalTime.of(9, 0);

    /** 午餐禁游窗口开始；input.lunchStart 为 null 时取此默认 */
    private LocalTime defaultLunchStart = LocalTime.of(11, 30);

    /** 午餐禁游窗口结束；input.lunchEnd 为 null 时取此默认 */
    private LocalTime defaultLunchEnd = LocalTime.of(14, 0);

    /** suggest_duration 缺失时的默认游玩时长（分钟）；input.defaultVisitMinutes 为 null 时取此默认 */
    private int defaultVisitMinutes = 90;

    /** 单日点数枚举护栏（聚类硬约束本就 ≤7，此值兜底防阶乘爆炸） */
    private int maxPoints = 10;
}
