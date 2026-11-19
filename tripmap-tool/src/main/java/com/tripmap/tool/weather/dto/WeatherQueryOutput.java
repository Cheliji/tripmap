package com.tripmap.tool.weather.dto;

import com.tripmap.tool.spi.ToolOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 天气查询工具输出 DTO（任务 2.3）。
 *
 * <p>{@code daily} 与输入日期区间逐日对齐（顺序不变，含降级日），供攻略节点
 * 按天装配天气素材；{@code degraded} 为整体降级信号——任一日超出预报窗口即为 true，
 * 供上游快速决策（TASKS 2.3.3）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherQueryOutput implements ToolOutput {

    /** 城市名（回显输入） */
    private String city;

    /** 逐日天气列表（顺序与输入区间一致，长度 = 输入 days） */
    private List<DailyWeather> daily;

    /** 整体降级标记：任一日超出预报窗口（无真实预报） */
    private boolean degraded;

    /** 数据来源归因（合规红线：必须展示「和风天气 QWeather」，见接口文档 §2.5） */
    private String attribution;
}
