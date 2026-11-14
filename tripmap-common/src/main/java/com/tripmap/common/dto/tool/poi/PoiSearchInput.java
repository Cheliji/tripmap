package com.tripmap.common.dto.tool.poi;

import com.tripmap.common.dto.tool.ToolInput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * POI 检索工具输入（任务 2.2，设计说明书 §6.1 硬过滤口径）。
 *
 * <p>字段全部由候选筛选节点以确定性代码装配：城市经白名单校验、
 * 必去景点名先回库解析为 ID、门票/时长上限由背景 Schema 按规则折算。
 * 注意 {@code openHours} 不参与检索条件（设计说明书 §8.1 消费拓扑），
 * 仅服务闭馆预检、时间窗剪枝与硬校验#3。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "POI 检索工具输入")
public class PoiSearchInput implements ToolInput {

    @Schema(description = "城市名（须经意图校验白名单过滤）")
    private String city;

    @Schema(description = "开放状态过滤：1正常 2闭园 3下架；null=不限", example = "1")
    private Integer status;

    @Schema(description = "门票价格上限（元）；null=不限。0=免费景点不受此限")
    private BigDecimal ticketPriceMax;

    @Schema(description = "建议游玩时长上限（分钟）；null=不限")
    private Integer maxDurationMin;

    @Schema(description = "偏好关键词（偏好标签/简介召回打分用，不参与 SQL 过滤）")
    private List<String> preferenceKeywords;

    @Schema(description = "必去景点 ID 列表（回库解析后的主键，锚定聚类用）")
    private List<Long> mustVisitPoiIds;

    @Schema(description = "期望候选数量（语义召回 20～40 个，设计说明书 §6.1）", example = "30")
    private Integer candidateLimit;
}
