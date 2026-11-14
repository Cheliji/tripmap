package com.tripmap.common.dto.tool.weather;

import com.tripmap.common.dto.tool.ToolInput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 天气查询工具输入（任务 2.3，设计说明书 §9）。
 *
 * <p>日期区间来自背景 Schema 的出行日期（候选筛选节点装配）；
 * 缓存 key = city + date（§8.3，TTL 30～60 分钟）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "天气查询工具输入")
public class WeatherQueryInput implements ToolInput {

    @Schema(description = "城市名")
    private String city;

    @Schema(description = "行程日期列表（逐日查询，无重复无遗漏）")
    private List<LocalDate> dates;
}
