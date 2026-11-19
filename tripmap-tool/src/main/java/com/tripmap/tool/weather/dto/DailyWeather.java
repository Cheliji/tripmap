package com.tripmap.tool.weather.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 归一化后的单日天气（任务 2.3，设计说明书 §8.4 输出结构建议）。
 *
 * <p>原始响应嵌套深、字段带单位对象（如 temperatureMax.value / unit），直接塞给
 * 模型既费 token 又易读错；本类在工具层压平成攻略节点（3.7）真正需要的字段，
 * 并统一单位与百分号口径（湿度/降水概率已 ×100）。</p>
 *
 * <p>{@code degraded=true} 表示该日超出预报窗口，无真实预报数据（除 date 外其余字段为 null），
 * 文案由攻略节点说明「临近出行再查」，模型不得自行编造天气（AGENTS.md 第 1 节原则 2）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyWeather {

    /** 当地日期（yyyy-MM-dd，取自 forecastStartTime 的当地日期部分） */
    private String date;

    /** 白天天气现象（condition.text，如"多云"） */
    private String dayCondition;

    /** 夜间天气现象（condition.text，如"晴"） */
    private String nightCondition;

    /** 当日最高温（°C） */
    private Double tempMaxC;

    /** 当日最低温（°C） */
    private Double tempMinC;

    /** 白天降水概率（0～100，原值 0～1 已 ×100） */
    private Double precipProbabilityPct;

    /** 白天累计降水量（mm） */
    private Double precipMm;

    /** 当日最大紫外线指数（0～15） */
    private Integer uvIndexMax;

    /** 是否降级：超出预报窗口，无真实预报（非降级日恒为 false） */
    @Builder.Default
    private boolean degraded = false;
}
