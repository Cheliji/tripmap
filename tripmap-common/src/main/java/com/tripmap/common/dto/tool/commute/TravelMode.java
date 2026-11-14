package com.tripmap.common.dto.tool.commute;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 通勤出行方式（任务 2.4，高德路径规划参数）。
 *
 * <p>同时是通勤缓存 key 的组成部分（§8.3：poi_id_a + poi_id_b + 出行方式）。</p>
 */
@Schema(description = "通勤出行方式")
public enum TravelMode {

    @Schema(description = "公交/地铁（市内游玩主口径）")
    TRANSIT,

    @Schema(description = "驾车")
    DRIVING
}
