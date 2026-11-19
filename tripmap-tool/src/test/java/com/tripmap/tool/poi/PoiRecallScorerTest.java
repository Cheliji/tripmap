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
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入与打分明细，console 可直接人工 review。</p>
 */
class PoiRecallScorerTest {

    private final PoiSearchProperties properties = new PoiSearchProperties();
    private final PoiRecallScorer scorer = new PoiRecallScorer(properties);

    // ---------- 人读日志辅助 ----------

    /** 打印打分场景：候选信息 → 权重输入 → 四项明细 → 总分 */
    private void printScore(String scenario, PoiCandidate c,
                            Map<String, Double> tagWeights, Map<String, Double> categoryWeights) {
        double tag = scorer.tagScore(c.getTags(), tagWeights);
        double cat = scorer.categoryScore(c.getCategory(), categoryWeights);
        double rating = scorer.ratingScore(c.getRating());
        double completeness = scorer.completenessBonus(c);
        double total = tag + cat + rating + completeness;
        System.out.println("""
                
                ========== 场景：%s ==========""".formatted(scenario));
        System.out.printf("  POI        : tags=%s, category=%s, rating=%s%n",
                c.getTags(), c.getCategory(), c.getRating());
        System.out.printf("  时长/门票/h: %s / %s / %s%n",
                c.getSuggestDuration(), c.getTicketPrice(), c.getOpenHours());
        System.out.printf("  标签权重   : %s%n", tagWeights);
        System.out.printf("  分类权重   : %s%n", categoryWeights);
        System.out.println("  ---- 打分明细 ----");
        System.out.printf("  标签均分   : %+.2f%n", tag);
        System.out.printf("  category   : %+.2f%n", cat);
        System.out.printf("  rating映射 : %+.2f%n", rating);
        System.out.printf("  完整度加分 : %+.2f%n", completeness);
        System.out.printf("  总分       : %+.2f%n", total);
    }

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

    // ---------- 用例 ----------

    @Test
    @DisplayName("标签均分：单标签命中取该权重")
    void tagScoreSingleHit() {
        PoiCandidate c = candidate(List.of(PoiTagVocab.FAMILY), null, null, null, null, null);
        Map<String, Double> weights = Map.of(PoiTagVocab.FAMILY, 4.0);
        printScore("单标签命中（亲子 +4 → 均分 +4）", c, weights, Map.of());
        double score = scorer.score(c, weights, Map.of());
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
        printScore("强命中单标签（期望均分 +5.00）", strong, weights, Map.of());
        printScore("一强一无关双标签（期望均分 +2.50）", mixed, weights, Map.of());
        assertEquals(5.0, scorer.score(strong, weights, Map.of()), 1e-9);
        assertEquals(2.5, scorer.score(mixed, weights, Map.of()), 1e-9);
    }

    @Test
    @DisplayName("标签均分：正负混合相互抵消")
    void tagScoreMixedPolarity() {
        PoiCandidate c = candidate(List.of(PoiTagVocab.FAMILY, PoiTagVocab.HIGH_STAMINA),
                null, null, null, null, null);
        Map<String, Double> weights = Map.of(PoiTagVocab.FAMILY, 5.0, PoiTagVocab.HIGH_STAMINA, -5.0);
        printScore("正负混合（亲子+5、高体力-5 → 均分 0，带老人出行高体力是减分项）", c, weights, Map.of());
        assertEquals(0.0, scorer.score(c, weights, Map.of()), 1e-9);
    }

    @Test
    @DisplayName("标签均分：无命中/空 tags/空权重均记 0")
    void tagScoreNoHit() {
        Map<String, Double> weights = Map.of(PoiTagVocab.FAMILY, 5.0);
        PoiCandidate noHit = candidate(List.of(PoiTagVocab.NICHE), null, null, null, null, null);
        printScore("标签未命中（小众不在权重表 → 0）", noHit, weights, Map.of());
        assertEquals(0.0, scorer.score(noHit, weights, Map.of()), 1e-9);
        PoiCandidate emptyTags = candidate(List.of(), null, null, null, null, null);
        printScore("tags 为空（冷门景点无标签 → 0）", emptyTags, weights, Map.of());
        assertEquals(0.0, scorer.score(emptyTags, weights, Map.of()), 1e-9);
        PoiCandidate nullTags = candidate(null, null, null, null, null, null);
        assertEquals(0.0, scorer.score(nullTags, weights, Map.of()), 1e-9);
    }

    @Test
    @DisplayName("category 权重计入总分，未赋值分类记 0")
    void categoryScoreApplied() {
        Map<String, Double> catWeights = Map.of(PoiCategory.CULTURE.getLabel(), 3.0);
        PoiCandidate c = candidate(List.of(), PoiCategory.CULTURE.getLabel(), null, null, null, null);
        printScore("分类命中（文博展馆 +3）", c, Map.of(), catWeights);
        assertEquals(3.0, scorer.score(c, Map.of(), catWeights), 1e-9);
        printScore("分类未赋值（空分类权重 → 0）", c, Map.of(), Map.of());
        assertEquals(0.0, scorer.score(c, Map.of(), Map.of()), 1e-9);
        // category 为 null（未分类）不抛异常
        PoiCandidate noCategory = candidate(List.of(), null, null, null, null, null);
        assertEquals(0.0, scorer.score(noCategory, Map.of(), catWeights), 1e-9);
    }

    @Test
    @DisplayName("rating 映射：取原值，null → 0，超出 ratingMax 截断")
    void ratingScoreMapping() {
        PoiCandidate rated = candidate(List.of(), null, "4.6", null, null, null);
        printScore("rating 4.6（原值映射 +4.60）", rated, Map.of(), Map.of());
        assertEquals(4.6, scorer.score(rated, Map.of(), Map.of()), 1e-9);
        PoiCandidate unknown = candidate(List.of(), null, null, null, null, null);
        printScore("rating 未知（null → 0）", unknown, Map.of(), Map.of());
        assertEquals(0.0, scorer.score(unknown, Map.of(), Map.of()), 1e-9);
        PoiCandidate overflow = candidate(List.of(), null, "9.9", null, null, null);
        printScore("rating 9.9 超界（截断到 +5.00）", overflow, Map.of(), Map.of());
        assertEquals(5.0, scorer.score(overflow, Map.of(), Map.of()), 1e-9);
    }

    @Test
    @DisplayName("完整度加分：三字段非空各 +1，封顶 +3；全 null 记 0")
    void completenessBonus() {
        PoiCandidate full = candidate(List.of(), null, null, 120, "0", "{}");
        printScore("三字段齐全（时长+门票+开放时段 → +3.00 封顶）", full, Map.of(), Map.of());
        assertEquals(3.0, scorer.completenessBonus(full), 1e-9);
        PoiCandidate partial = candidate(List.of(), null, null, 120, null, null);
        printScore("仅时长非空（→ +1.00）", partial, Map.of(), Map.of());
        assertEquals(1.0, scorer.completenessBonus(partial), 1e-9);
        PoiCandidate empty = candidate(List.of(), null, null, null, null, null);
        printScore("全未知（→ 0，冷门无信源景点排在池底）", empty, Map.of(), Map.of());
        assertEquals(0.0, scorer.completenessBonus(empty), 1e-9);
    }

    @Test
    @DisplayName("完整度加分：open_hours 空白串视为未知，不计分")
    void completenessBlankOpenHours() {
        PoiCandidate blank = candidate(List.of(), null, null, 120, "0", "   ");
        printScore("开放时段为空白串（视为未知 → 只计时长+门票 = +2.00）", blank, Map.of(), Map.of());
        assertEquals(2.0, scorer.completenessBonus(blank), 1e-9);
    }

    @Test
    @DisplayName("权重归一化：词表外键丢弃，越界值截断到 ±weightBound，null 输入返回空 Map")
    void normalizeWeights() {
        Map<String, Double> raw = Map.of(
                PoiTagVocab.FAMILY, 7.0,          // 上越界 → 5.0
                PoiTagVocab.HIGH_STAMINA, -8.0,   // 下越界 → -5.0
                "词表外标签", 3.0);                  // 丢弃
        System.out.println("""
                
                ========== 场景：权重归一化（LLM 输出防护） ==========""");
        System.out.printf("  LLM 原始输出: %s%n", raw);
        Map<String, Double> normalized = scorer.normalizeWeights(raw, PoiTagVocab.ALL, "标签");
        System.out.printf("  归一化结果  : %s（7→5、-8→-5、词表外丢弃）%n", normalized);
        assertEquals(2, normalized.size());
        assertEquals(5.0, normalized.get(PoiTagVocab.FAMILY), 1e-9);
        assertEquals(-5.0, normalized.get(PoiTagVocab.HIGH_STAMINA), 1e-9);
        assertTrue(scorer.normalizeWeights(null, PoiTagVocab.ALL, "标签").isEmpty());
        assertTrue(scorer.normalizeWeights(Map.of(), PoiTagVocab.ALL, "标签").isEmpty());
        // 分类词表独立校验
        Map<String, Double> catRaw = Map.of("不存在的分类", 5.0);
        System.out.printf("  分类侧词表外: %s → 空 Map%n", catRaw);
        assertTrue(scorer.normalizeWeights(catRaw, PoiCategory.labels(), "分类").isEmpty());
    }
}
