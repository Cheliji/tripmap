package com.tripmap.common.dto.tool.cluster;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 聚类空间点（任务 2.5）：POI 的经纬度投影，距离计算用 haversine 球面距离。
 *
 * @param poiId     POI 主键
 * @param longitude 经度 GCJ-02
 * @param latitude  纬度 GCJ-02
 */
@Schema(description = "聚类空间点")
public record PoiPoint(@Schema(description = "POI 主键") Long poiId,
                       @Schema(description = "经度 GCJ-02") BigDecimal longitude,
                       @Schema(description = "纬度 GCJ-02") BigDecimal latitude) {
}
