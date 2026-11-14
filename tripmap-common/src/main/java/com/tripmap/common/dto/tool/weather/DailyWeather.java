package com.tripmap.common.dto.tool.weather;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 逐日天气（任务 2.3.3 远期降级：查询日期超出预报能力时 {@code degraded=true}，
 * 数据为历史气候参考，文案由攻略节点说明）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "逐日天气")
public class DailyWeather {

    @Schema(description = "日期")
    private LocalDate date;

    @Schema(description = "最低气温（℃）")
    private Integer tempMinC;

    @Schema(description = "最高气温（℃）")
    private Integer tempMaxC;

    @Schema(description = "天气现象文本（晴/多云/小雨…）")
    private String weatherText;

    @Schema(description = "降水概率（%）；供应商不支持则为 null")
    private Integer precipitationPercent;

    @Schema(description = "是否远期降级（true=历史气候参考值，非实时预报）", example = "false")
    private Boolean degraded;
}
