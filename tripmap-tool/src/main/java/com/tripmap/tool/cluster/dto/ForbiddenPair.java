package com.tripmap.tool.cluster.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 禁配点对（任务 2.5.9，设计说明书 §6.2"通勤时间禁配回切环"）：
 * 两个 POI 不得排在同一天。由编排层（3.6）在通勤矩阵 / 硬校验 #4
 * 发现"同天但通勤超时"时注入，回灌聚类模型重解。
 *
 * <p>CP-SAT 建模为 x[i][d] + x[j][d] ≤ 1（对全部天数 d）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForbiddenPair {

    /** 第一个 POI id */
    private Long firstPoiId;

    /** 第二个 POI id */
    private Long secondPoiId;
}
