package com.tripmap.tool.poi;

import com.tripmap.common.constant.PoiCategory;
import com.tripmap.common.constant.PoiTagVocab;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回打分器单测（任务 2.2.3）。
 *
 * <p>打分式口径见 {@link PoiRecallScorer}：标签均分 + category 权重 +
 * rating 映射 + 完整度加分；权重归一化 = 词表闭集校验 + 越界截断。</p>
 */
class PoiRecallScorerTest {

    private final PoiSearchProperties properties = new PoiSearchProperties();
    private final PoiRecallScorer scorer = new PoiRecallScorer(properties);

    private PoiCandidate candidate(List<String> tags, String category, String rating,
                                   Integer duration, String ticket, String openHours) {
        return PoiCandidate.builder()
                .id(1L)
                .tags(tags)
                .category(category)
                .rating(rating == null ? null : new BigDecimal(rating))
                .suggestDuration(duration)
                .ticketPrice(ticket == null ? null : new BigDecimal(ticket))
                .openHours(openHours)
                .build();
    }

    @Test
    @DisplayName("标签均分：单标签命中取该权重")
    void tagScoreSingleHit() {
        PoiCandidate c = candidate(List.of(PoiTagVocab.FAMILY), null, null, null, null, null);
        double score = scorer.score(c, Map.of(PoiTagVocab.FAMILY, 4.0), Map.of());
        assertEquals(4.0, score, 1e-9);
    }

    @Test
    @DisplayName("标签均分：多标签取平均，防'标签多=分数高'偏置")
    void tagScoreAveraged() {
        // 1 个强相关（+5）应与 2 个"一强一无关"（(+5+0)/2=2.5）按均值拉开差距
        PoiCandidate strong = candidate(List.of(PoiTagVocab.FAMILY), null, null, null, null, null);
        PoiCandidate mixed = candidate(List.of(PoiTagVocab.FAMILY, PoiTagVocab.NICHE),
                null, null, null, null, null);
        Map<String, Double> weights = Map.of(PoiTagVocab.FAMILY, 5.0);
        assertEquals(5.0, scorer.score(strong, weights, Map.of()), 1e-9);
        assertEquals(2.5, scorer.score(mixed, weights, Map.of()), 1e-9);
    }

    @Test
    @DisplayName("标签均分：正负混合相互抵消")
    void tagScoreMixedPolarity() {
        PoiCandidate c = candidate(List.of(PoiTagVocab.FAMILY, PoiTagVocab.HIGH_STAMINA),
                null, null, null, null, null);
        Map<String, Double> weights = Map.of(PoiTagVocab.FAMILY, 5.0, PoiTagVocab.HIGH_STAMINA, -5.0);
        assertEquals(0.0, scorer.score(c, weights, Map.of()), 1e-9);
    }

    @Test
    @DisplayName("标签均分：无命中/空 tags/空权重均记 0")
    void tagScoreNoHit() {
        PoiCandidate noHit = candidate(List.of(PoiTagVocab.NICHE), null, null, null, null, null);
        assertEquals(0.0, scorer.score(noHit, Map.of(PoiTagVocab.FAMILY, 5.0), Map.of()), 1e-9);
        PoiCandidate emptyTags = candidate(List.of(), null, null, null, null, null);
        assertEquals(0.0, scorer.score(emptyTags, Map.of(PoiTagVocab.FAMILY, 5.0), Map.of()), 1e-9);
        PoiCandidate nullTags = candidate(null, null, null, null, null, null);
        assertEquals(0.0, scorer.score(nullTags, Map.of(PoiTagVocab.FAMILY, 5.0), Map.of()), 1e-9);
    }

    @Test
    @DisplayName("category 权重计入总分，未赋值分类记 0")
    void categoryScoreApplied() {
        PoiCandidate c = candidate(List.of(), PoiCategory.CULTURE.getLabel(), null, null, null, null);
        assertEquals(3.0, scorer.score(c, Map.of(), Map.of(PoiCategory.CULTURE.getLabel(), 3.0)), 1e-9);
        assertEquals(0.0, scorer.score(c, Map.of(), Map.of()), 1e-9);
        // category 为 null（未分类）不抛异常
        PoiCandidate noCategory = candidate(List.of(), null, null, null, null, null);
        assertEquals(0.0, scorer.score(noCategory, Map.of(), Map.of(PoiCategory.CULTURE.getLabel(), 3.0)), 1e-9);
    }

    @Test
    @DisplayName("rating 映射：取原值，null → 0，超出 ratingMax 截断")
    void ratingScoreMapping() {
        PoiCandidate rated = candidate(List.of(), null, "4.6", null, null, null);
        assertEquals(4.6, scorer.score(rated, Map.of(), Map.of()), 1e-9);
        PoiCandidate unknown = candidate(List.of(), null, null, null, null, null);
        assertEquals(0.0, scorer.score(unknown, Map.of(), Map.of()), 1e-9);
        PoiCandidate overflow = candidate(List.of(), null, "9.9", null, null, null);
        assertEquals(5.0, scorer.score(overflow, Map.of(), Map.of()), 1e-9);
    }

    @Test
    @DisplayName("完整度加分：三字段非空各 +1，封顶 +3；全 null 记 0")
    void completenessBonus() {
        PoiCandidate full = candidate(List.of(), null, null, 120, "0", "{}");
        assertEquals(3.0, scorer.completenessBonus(full), 1e-9);
        PoiCandidate partial = candidate(List.of(), null, null, 120, null, null);
        assertEquals(1.0, scorer.completenessBonus(partial), 1e-9);
        PoiCandidate empty = candidate(List.of(), null, null, null, null, null);
        assertEquals(0.0, scorer.completenessBonus(empty), 1e-9);
    }

    @Test
    @DisplayName("完整度加分：open_hours 空白串视为未知，不计分")
    void completenessBlankOpenHours() {
        PoiCandidate blank = candidate(List.of(), null, null, 120, "0", "   ");
        assertEquals(2.0, scorer.completenessBonus(blank), 1e-9);
    }

    @Test
    @DisplayName("权重归一化：词表外键丢弃，越界值截断到 ±weightBound，null 输入返回空 Map")
    void normalizeWeights() {
        Map<String, Double> raw = Map.of(
                PoiTagVocab.FAMILY, 7.0,          // 上越界 → 5.0
                PoiTagVocab.HIGH_STAMINA, -8.0,   // 下越界 → -5.0
                "词表外标签", 3.0);                  // 丢弃
        Map<String, Double> normalized = scorer.normalizeWeights(raw, PoiTagVocab.ALL, "标签");
        assertEquals(2, normalized.size());
        assertEquals(5.0, normalized.get(PoiTagVocab.FAMILY), 1e-9);
        assertEquals(-5.0, normalized.get(PoiTagVocab.HIGH_STAMINA), 1e-9);
        assertTrue(scorer.normalizeWeights(null, PoiTagVocab.ALL, "标签").isEmpty());
        assertTrue(scorer.normalizeWeights(Map.of(), PoiTagVocab.ALL, "标签").isEmpty());
        // 分类词表独立校验
        Map<String, Double> catRaw = Map.of("不存在的分类", 5.0);
        assertTrue(scorer.normalizeWeights(catRaw, PoiCategory.labels(), "分类").isEmpty());
    }
}
