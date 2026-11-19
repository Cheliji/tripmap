package com.tripmap.tool.poi;

import com.tripmap.tool.spi.ToolInput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * POI 检索工具输入 DTO（任务 2.2，设计说明书 §6.1 候选筛选）。
 *
 * <p>由编排节点从图 State（背景 Schema + 偏好解析结果）确定性装配，非模型生成。
 * 标签/分类权重来自偏好解析节点（3.3）LLM 按用户背景赋值，取值 [-5, +5] 浮点
 * （2026-11 决策）；词表闭集校验与越界截断收敛在 {@link PoiRecallScorer}。</p>
 *
 * <p>NULL 语义（推广 open_hours 三态，设计说明书 §8.1 2026-11 修订）：
 * 两个上限字段为 null 表示<b>无该约束</b>；POI 侧 ticket_price / suggest_duration
 * 为 null（未知）时一律放行不参与对应过滤，消费方对输出 DTO 判空。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoiSearchInput implements ToolInput {

    /** 城市名（必填，MVP 仅"杭州"） */
    private String city;

    /** 门票预算上限（元）；null = 无预算约束 */
    private BigDecimal ticketPriceCap;

    /** 建议游玩时长上限（分钟，每日可用时间）；null = 无时长约束 */
    private Integer suggestDurationCapMinutes;

    /** 标签权重映射：20 词表标签（PoiTagVocab）→ [-5, +5] 浮点；null/空 = 无偏好 */
    @Builder.Default
    private Map<String, Double> tagWeights = Map.of();

    /** 分类权重映射：8 分类中文标签（PoiCategory）→ [-5, +5] 浮点；null/空 = 无偏好 */
    @Builder.Default
    private Map<String, Double> categoryWeights = Map.of();

    /** 必去景点 ID 列表（钉住：不参与排序、不受截断影响）；名称→ID 解析由上游节点负责 */
    @Builder.Default
    private List<Long> mustVisitIds = List.of();

    /** 召回截断上限（20～40，越界收敛到配置区间；<=0 取默认 40） */
    @Builder.Default
    private int limit = 40;
}
