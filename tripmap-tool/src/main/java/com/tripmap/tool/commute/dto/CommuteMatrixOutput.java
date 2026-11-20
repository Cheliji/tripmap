package com.tripmap.tool.commute.dto;

import com.tripmap.tool.commute.CommuteMode;
import com.tripmap.tool.spi.ToolOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通勤矩阵工具输出（任务 2.4）。
 *
 * <p>对称矩阵：按无序对 (i&lt;j) 调用高德一次后镜像填充（省一半配额，
 * 通勤近似方向无关）。{@link #durationsSeconds}{@code [i][j]} 单位秒，
 * 对角线为 0，不可达/降级项为 -1；缺失对同时以 {@link #missingPairs}
 * 明文列出（"idA→idB"，idA&lt;idB），供 2.6 TSP 与 3.8 评测消费。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommuteMatrixOutput implements ToolOutput {

    /** 矩阵行列对应的 POI ID 顺序（与输入去重后顺序一致） */
    private List<Long> poiIds;

    /** 通勤耗时矩阵（秒）；[i][j] = i→j，-1 = 缺失，对角线 0 */
    private long[][] durationsSeconds;

    /** 缺失对列表（"idA→idB"）；非空时 {@link #degraded} 为 true */
    private List<String> missingPairs;

    /** 出行方式 */
    private CommuteMode mode;

    /** 本次实际调用高德的对数（缓存命中验证：重复规划应为 0，TASKS 2.4.3） */
    private int apiCallCount;

    /** 本次 Redis 缓存命中的对数 */
    private int cacheHitCount;

    /** 存在缺失但未到不可行阈值（true 时矩阵仍可用，评测节点可见） */
    private boolean degraded;
}
