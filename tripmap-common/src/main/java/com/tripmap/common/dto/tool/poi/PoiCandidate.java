package com.tripmap.common.dto.tool.poi;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * POI 候选视图 DTO（任务 2.1.2）：工具层对外暴露的统一 POI 形状。
 *
 * <p>与 dal 的 {@code PoiEntity} 解耦：entity 加字段/换 ORM 不影响编排层与前端；
 * 同时是 LLM 受约束精选（3.5）与 ID 回库校验的比对载体——LLM 只能从此清单的
 * {@code poiId} 中选择，清单外 ID 直接拒绝。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "POI 候选视图")
public class PoiCandidate {

    @Schema(description = "POI 主键")
    private Long poiId;

    @Schema(description = "名称快照")
    private String name;

    @Schema(description = "城市")
    private String city;

    @Schema(description = "区县行政区划码 GB/T 2260")
    private Integer districtAdcode;

    @Schema(description = "经度 GCJ-02")
    private BigDecimal longitude;

    @Schema(description = "纬度 GCJ-02")
    private BigDecimal latitude;

    @Schema(description = "项目内分类（8 枚举中文标签，PoiCategory）")
    private String category;

    @Schema(description = "标签（受控词表闭集 20 个，已解析为列表）")
    private List<String> tags;

    @Schema(description = "高德评分")
    private BigDecimal rating;

    @Schema(description = "门票价格（元），0=免费")
    private BigDecimal ticketPrice;

    @Schema(description = "建议游玩时长（分钟）")
    private Integer suggestDuration;

    @Schema(description = "结构化开放时段 JSON 原文（mon~sun 七键；[]=确定闭馆；键 null/整列空=未知）")
    private String openHours;

    @Schema(description = "简介（候选精筛素材）")
    private String brief;

    @Schema(description = "开放状态：1正常 2闭园 3下架")
    private Integer status;
}
