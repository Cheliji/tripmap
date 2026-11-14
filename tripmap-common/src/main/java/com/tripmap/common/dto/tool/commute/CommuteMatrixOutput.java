package com.tripmap.common.dto.tool.commute;

import com.tripmap.common.dto.tool.ToolOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 通勤矩阵工具输出（任务 2.4）。
 *
 * <p>{@code matrixMinutes} 的 key 统一用 {@link #keyOf(long, long)} 生成
 * （小 ID 在前，保证 a→b 与 b→a 同 key，矩阵天然对称）；
 * value 为分钟。缺失的 key = 该对调用失败且重试/降级无效，
 * 由下游（2.6.2 时间窗剪枝、3.6 装配节点）按缺失值策略处理，禁止工具内部静默补 0。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "通勤矩阵工具输出")
public class CommuteMatrixOutput implements ToolOutput {

    @Schema(description = "两两通勤时间矩阵（key 格式见 keyOf；单位分钟）")
    private Map<String, Integer> matrixMinutes;

    @Schema(description = "实际调用高德 API 的对数（缓存命中不计入，验证重复规划零 API 调用）")
    private Integer apiCallCount;

    /**
     * 矩阵 key 规范：小 ID 在前的 "{a}_{b}"，a→b 与 b→a 天然同 key（矩阵对称性，任务 2.4.4）。
     */
    public static String keyOf(long poiIdA, long poiIdB) {
        return poiIdA < poiIdB ? poiIdA + "_" + poiIdB : poiIdB + "_" + poiIdA;
    }
}
