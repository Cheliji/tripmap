package com.tripmap.tool.poi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 召回候选 POI（任务 2.2.2 输出项）。
 *
 * <p>字段口径与 {@code PoiEntity} 一致；ticket_price / suggest_duration / rating /
 * open_hours 为 null 即"未知"，消费方（时间线组装、攻略节点）直接判空，
 * 不设 Unknown 标记（2026-11 决策）。坐标随候选带出，供 2.5 聚类直接消费。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoiCandidate {

    private Long id;

    private String name;

    /** 行政区名（展示用） */
    private String district;

    /** 经度 GCJ-02（聚类/通勤输入） */
    private BigDecimal longitude;

    /** 纬度 GCJ-02（聚类/通勤输入） */
    private BigDecimal latitude;

    /** 项目内分类（8 枚举中文标签，PoiCategory） */
    private String category;

    /** 标签列表（受控词表闭集 20 个；JSON 解析失败按空列表处理） */
    private List<String> tags;

    /** 高德评分；null = 未知 */
    private BigDecimal rating;

    /** 门票价格（元），0 = 免费；null = 未知 */
    private BigDecimal ticketPrice;

    /** 建议游玩时长（分钟）；null = 未知（时间线组装按 category 默认时长表补齐） */
    private Integer suggestDuration;

    /** 结构化开放时段 JSON（3.5.3 闭馆预检 / 2.6.2 时间窗剪枝消费）；null = 未知 */
    private String openHours;

    /** 简介（3.5 LLM 契约精筛素材） */
    private String brief;

    /** 召回打分明细汇总值（标签均值 + category 权重 + rating 映射 + 完整度加分） */
    private double score;

    /** 是否必去景点（钉住项，不参与排序、不受截断影响） */
    private boolean mustVisit;
}
