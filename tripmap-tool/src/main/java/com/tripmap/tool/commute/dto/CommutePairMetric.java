package com.tripmap.tool.commute.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单对 POI 通勤度量（任务 2.4）：耗时 + 距离，Redis 缓存与工具内部传输的载体。
 *
 * <p>{@code durationSeconds == null} 表示不可达（高德返回空方案）或降级（API 失败），
 * 矩阵层按缺失处理；本工具禁止用直线距离或估算值填充缺失（AGENTS.md 原则 1）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommutePairMetric {

    /** 通勤耗时（秒）；null = 不可达/降级 */
    private Long durationSeconds;

    /** 通勤距离（米）；null = 高德未返回（缺失值处理同耗时，仅日志展示用） */
    private Long distanceMeters;
}
