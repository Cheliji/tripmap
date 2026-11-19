package com.tripmap.tool.poi;

import com.tripmap.common.constant.PoiCategory;
import com.tripmap.common.constant.PoiTagVocab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 召回打分器（任务 2.2.2，设计说明书 §6.1 第 2 步"标签加权召回"）。
 *
 * <p><b>打分式</b>（2026-11 定案，各项独立有界，总分范围 [-10, +18]）：</p>
 * <pre>
 * score = 标签均分          // POI 全部 tags 对应 LLM 权重的平均值（防"标签多=分数高"偏置），无命中 = 0
 *       + category 权重     // LLM 按背景对 8 分类赋值，未赋值 = 0
 *       + rating 映射       // 取原值（0~5 天然刻度），NULL → 0
 *       + 完整度加分         // suggest_duration / ticket_price / open_hours 非空各加分，封顶配置值
 * </pre>
 *
 * <p>权重前置说明：标签与分类的权重不是代码写死的，而是由偏好解析节点（3.3）
 * 的 LLM 按用户背景输出 [-5, +5] 浮点值；本类只负责词表闭集校验、越界截断与
 * 确定性聚合（AGENTS.md §1：几何/排序正确性由算法保证）。</p>
 *
 * <p>{@code ticket_price} / {@code suggest_duration} 只过滤不打分（TASKS 2.2.2：
 * 避免与 SQL 硬过滤双重计数，且"便宜/时长长短该加该减"方向语义不明）。</p>
 */
public class PoiRecallScorer {

    private static final Logger log = LoggerFactory.getLogger(PoiRecallScorer.class);

    private final PoiSearchProperties properties;

    public PoiRecallScorer(PoiSearchProperties properties) {
        this.properties = properties;
    }

    /**
     * 对单个候选打分。
     *
     * @param candidate       候选（tags 已解析为列表；null 字段即未知）
     * @param tagWeights      已归一化的标签权重（{@link #normalizeWeights} 产出）
     * @param categoryWeights 已归一化的分类权重
     * @return 综合得分
     */
    public double score(PoiCandidate candidate, Map<String, Double> tagWeights,
                        Map<String, Double> categoryWeights) {
        return tagScore(candidate.getTags(), tagWeights)
                + categoryScore(candidate.getCategory(), categoryWeights)
                + ratingScore(candidate.getRating())
                + completenessBonus(candidate);
    }

    /**
     * 标签均分：POI 全部 tags 对应权重的平均值——一个强相关标签（+5）应与
     * 五个弱相关标签（+1）打平，而不是碾压；tags 为空或全部未命中（权重 0）记 0。
     * public：除包内单测外，供真实库验证/评测归因打印打分明细。
     */
    public double tagScore(List<String> tags, Map<String, Double> tagWeights) {
        if (tags == null || tags.isEmpty() || tagWeights.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (String tag : tags) {
            sum += tagWeights.getOrDefault(tag, 0.0);
        }
        return sum / tags.size();
    }

    /** 分类权重：未赋值（用户未表达该分类偏好）记 0 */
    public double categoryScore(String category, Map<String, Double> categoryWeights) {
        if (category == null) {
            return 0.0;
        }
        return categoryWeights.getOrDefault(category, 0.0);
    }

    /** rating 映射：天然 0~5 刻度取原值，null → 0，超出 ratingMax 截断 */
    public double ratingScore(java.math.BigDecimal rating) {
        if (rating == null) {
            return 0.0;
        }
        return Math.min(Math.max(rating.doubleValue(), 0.0), properties.getRatingMax());
    }

    /**
     * 完整度加分：suggest_duration / ticket_price / open_hours 非空各加
     * {@code completenessBonusPerField}，封顶 {@code completenessBonusCap}——
     * 信源齐全的景点优先，未知数据的冷门景点作池底补充（2026-11 决策）。
     */
    public double completenessBonus(PoiCandidate candidate) {
        int nonNull = 0;
        if (candidate.getSuggestDuration() != null) {
            nonNull++;
        }
        if (candidate.getTicketPrice() != null) {
            nonNull++;
        }
        if (candidate.getOpenHours() != null && !candidate.getOpenHours().isBlank()) {
            nonNull++;
        }
        return Math.min(nonNull * properties.getCompletenessBonusPerField(),
                properties.getCompletenessBonusCap());
    }

    /**
     * 权重归一化：词表闭集校验 + 越界截断（TASKS 2.2.2）。
     *
     * <p>词表外键直接丢弃并 warn（LLM 输出漂移防护，闭集定义见
     * {@link PoiTagVocab} / {@link PoiCategory}）；绝对值越界截断到
     * ±weightBound 并 warn；null 值忽略。返回值保证不含 null、不含词表外键。</p>
     *
     * @param raw    LLM 原始权重映射；null/空 视为无偏好，返回空 Map
     * @param vocab  合法键闭集（标签 = PoiTagVocab.ALL，分类 = PoiCategory.labels()）
     * @param kind   日志用类别名（"标签"/"分类"）
     */
    public Map<String, Double> normalizeWeights(Map<String, Double> raw,
                                                Set<String> vocab, String kind) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        double bound = properties.getWeightBound();
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            if (key == null || !vocab.contains(key)) {
                log.warn("POI 召回{}权重丢弃词表外键: {}", kind, key);
                continue;
            }
            if (value == null) {
                continue;
            }
            double weight = value;
            if (weight > bound) {
                log.warn("POI 召回{}权重越界截断: {}={} → {}", kind, key, weight, bound);
                weight = bound;
            } else if (weight < -bound) {
                log.warn("POI 召回{}权重越界截断: {}={} → {}", kind, key, weight, -bound);
                weight = -bound;
            }
            normalized.put(key, weight);
        }
        return normalized;
    }
}
