package com.tripmap.common.dto.tool.weather;

import com.tripmap.common.dto.tool.ToolOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 天气查询工具输出（任务 2.3）：行程日期逐日天气。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "天气查询工具输出")
public class WeatherQueryOutput implements ToolOutput {

    @Schema(description = "城市名")
    private String city;

    @Schema(description = "逐日天气（与输入 dates 等长；某天查询失败可为空位缺失，由节点降级处理）")
    private List<DailyWeather> dailyWeathers;
}
