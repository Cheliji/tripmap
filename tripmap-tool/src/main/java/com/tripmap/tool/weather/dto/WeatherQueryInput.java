package com.tripmap.tool.weather.dto;

import com.tripmap.tool.spi.ToolInput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 天气查询工具输入 DTO（任务 2.3，TASKS 2.3.2「城市+日期区间」逐日查询）。
 *
 * <p>由编排节点从图 State（背景 Schema 的城市与出行日期）确定性装配，非模型生成。
 * MVP 单城市（杭州），天气为<b>城市级</b>粒度：一天一个区域的日级建议
 * （带伞/防晒/穿衣）只需城市级，不做景点级 1km 网格（2026-11 决策）。</p>
 *
 * <p>{@code days} 为行程天数，区间 [1, 15]：MVP 多日行程通常 ≤ 15 天；
 * 超过 10 天的尾部日期会由工具按方案 A（TASKS 2.3.3）标记 {@code degraded}，
 * 不做 API 真实预报。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherQueryInput implements ToolInput {

    /** 城市名（必填，MVP 仅"杭州"；工具层仍做一次防御性白名单校验） */
    private String city;

    /** 出行起始日（必填，含） */
    private LocalDate startDate;

    /** 行程天数（1～15，含；与 startDate 共同构成 [startDate, startDate+days) 闭开区间） */
    @Builder.Default
    private int days = 1;
}
