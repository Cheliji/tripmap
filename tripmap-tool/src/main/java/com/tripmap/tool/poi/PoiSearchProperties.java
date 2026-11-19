package com.tripmap.tool.poi;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * POI 检索工具配置（任务 2.2.2，权重与系数全部配置化）。
 *
 * <p>打分式见 {@link PoiRecallScorer}；区间与默认值按 2026-11 定案，
 * 调优只改 application.yml，不动代码。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tripmap.poi-search")
public class PoiSearchProperties {

    /** 召回截断下限（池不足此值时置 poolExhausted） */
    private int recallLimitMin = 20;

    /** 召回截断上限（默认 limit 收敛上界） */
    private int recallLimitMax = 40;

    /** 默认截断条数（输入 limit <= 0 时取用） */
    private int recallLimitDefault = 40;

    /** LLM 标签/分类权重的上下界（越界截断到边界 + warn） */
    private double weightBound = 5.0;

    /** rating 映射上限（高德评分 0~5 天然刻度；超出截断到此值） */
    private double ratingMax = 5.0;

    /** 完整度加分：单字段（suggest_duration / ticket_price / open_hours）非空加分 */
    private double completenessBonusPerField = 1.0;

    /** 完整度加分封顶 */
    private double completenessBonusCap = 3.0;
}
