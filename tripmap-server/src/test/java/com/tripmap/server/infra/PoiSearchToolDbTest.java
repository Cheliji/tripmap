package com.tripmap.server.infra;

import com.tripmap.common.constant.PoiCategory;
import com.tripmap.common.constant.PoiTagVocab;
import com.tripmap.tool.poi.PoiCandidate;
import com.tripmap.tool.poi.PoiRecallScorer;
import com.tripmap.tool.poi.PoiSearchInput;
import com.tripmap.tool.poi.PoiSearchOutput;
import com.tripmap.tool.poi.PoiSearchTool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * POI 检索工具真实库验证（任务 2.2.3 补充，人工 review 用）。
 *
 * <p>与 {@link MySqlConnectivitySmokeTest} 同一约定：本地 MySQL 不可达或
 * 杭州 POI 未入库时<b>跳过而非失败</b>；开发机数据就绪时本用例必须真实通过。</p>
 *
 * <p>按 AGENTS.md 定规打印完整人读日志：偏好权重输入 → 候选池概况 →
 * 打分明细 → 召回结果表，console 可直接当演示看。</p>
 */
@SpringBootTest(properties = "tripmap.amap.api-key=test-placeholder-key")
class PoiSearchToolDbTest {

    private static final String DB_HOST = "127.0.0.1";
    private static final int DB_PORT = 3306;

    /** 模拟偏好解析节点（3.3）LLM 按背景输出的权重：一家三口带老人，重文化、忌高强度 */
    private static final Map<String, Double> TAG_WEIGHTS = Map.of(
            PoiTagVocab.FAMILY, 5.0,        // 亲子：强想要
            PoiTagVocab.SENIOR_FRIENDLY, 4.0, // 老人友好：强想要
            PoiTagVocab.CULTURE_DEEP, 3.0,  // 文化深度：想要
            PoiTagVocab.HIGH_STAMINA, -4.0, // 高体力：带老人出行是减分项
            PoiTagVocab.SHOPPING_DINING, -1.0); // 购物餐饮：略回避

    private static final Map<String, Double> CATEGORY_WEIGHTS = Map.of(
            PoiCategory.CULTURE.getLabel(), 4.0,   // 文博展馆：偏爱
            PoiCategory.SCENERY.getLabel(), 3.0,   // 名胜自然：偏爱
            PoiCategory.HERITAGE.getLabel(), 3.0,  // 人文古迹：偏爱
            PoiCategory.BUSINESS.getLabel(), -2.0);// 商圈街区：回避

    @Autowired
    private PoiSearchTool poiSearchTool;

    @Autowired
    private PoiRecallScorer scorer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void checkMySqlReachable() {
        assumeTrue(portOpen(DB_HOST, DB_PORT),
                "本地 MySQL 未监听 %s:%d，跳过真实库验证".formatted(DB_HOST, DB_PORT));
    }

    @Test
    @DisplayName("真实库召回：带偏好权重的杭州候选检索（人工 review 日志）")
    void searchWithWeightsAgainstRealDb() {
        // 库里 city 存高德原始城市名（如"杭州市"），口径与采集脚本一致
        Integer hangzhouCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poi WHERE city = '杭州市' AND status = 1", Integer.class);
        assumeTrue(hangzhouCount != null && hangzhouCount > 0,
                "杭州 POI 未入库，跳过真实库验证（先跑 tripmap-poi 三脚本）");

        // ---- 输入：模拟"两大人+一儿童+一老人，文化偏好"行程背景 ----
        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州市")
                .ticketPriceCap(null)            // 未设预算：观察无约束下的纯偏好召回
                .suggestDurationCapMinutes(480)  // 单日可用 8 小时
                .tagWeights(TAG_WEIGHTS)
                .categoryWeights(CATEGORY_WEIGHTS)
                .limit(40)
                .build();

        PoiSearchOutput out = poiSearchTool.execute(input);

        // ---- 人读日志 ----
        System.out.println("\n########## 场景：真实库召回（杭州，家庭文化偏好） ##########");
        System.out.println("  背景模拟 : 两大人+儿童+老人；重亲子/老人友好/文化，回避高体力与购物");
        System.out.printf("  标签权重 : %s%n", TAG_WEIGHTS);
        System.out.printf("  分类权重 : %s%n", CATEGORY_WEIGHTS);
        System.out.printf("  池大小=%d（库中杭州正常 POI=%d）, 池耗尽=%s, 必去冲突=%s%n",
                out.getPoolSize(), hangzhouCount, out.isPoolExhausted(), out.getMustVisitConflictIds());

        List<PoiCandidate> candidates = out.getCandidates();
        System.out.println("  ---- 打分明细（前 3 名）----");
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            PoiCandidate c = candidates.get(i);
            System.out.printf("  [%d] %s: 标签均分%+.2f + 分类%+.2f + rating%+.2f + 完整度%+.2f = %+.2f%n",
                    i + 1, c.getName(),
                    scorer.tagScore(c.getTags(), TAG_WEIGHTS),
                    scorer.categoryScore(c.getCategory(), CATEGORY_WEIGHTS),
                    scorer.ratingScore(c.getRating()),
                    scorer.completenessBonus(c),
                    c.getScore());
        }

        System.out.println("  ---- 召回结果表 ----");
        System.out.printf("  %-4s %-8s %-24s %-12s %-32s %-9s %-6s %-6s %-6s%n",
                "名次", "ID", "名称", "分类", "标签", "总分", "rating", "门票", "时长");
        for (int i = 0; i < candidates.size(); i++) {
            PoiCandidate c = candidates.get(i);
            System.out.printf("  %-4d %-8d %-24s %-12s %-32s %+8.2f %-6s %-6s %-6s%n",
                    i + 1, c.getId(), abbreviate(c.getName(), 24), nullToDash(c.getCategory()),
                    abbreviate(String.join(",", nullToEmpty(c.getTags())), 32),
                    c.getScore(),
                    c.getRating() == null ? "未知" : c.getRating().toPlainString(),
                    c.getTicketPrice() == null ? "未知" : c.getTicketPrice().toPlainString(),
                    c.getSuggestDuration() == null ? "未知" : c.getSuggestDuration() + "分");
        }

        // ---- 机器校验（宽松口径：真实数据内容不定，只验证算法行为）----
        assertFalse(candidates.isEmpty(), "候选不应为空");
        assertTrue(candidates.size() <= 40, "截断上限 40");
        assertTrue(out.getPoolSize() >= candidates.size(), "池大小应不小于输出数");
        for (int i = 1; i < candidates.size(); i++) {
            assertTrue(candidates.get(i - 1).getScore() >= candidates.get(i).getScore() - 1e-9,
                    "输出应按打分降序: 第%d名(%.2f) < 第%d名(%.2f)"
                            .formatted(i, candidates.get(i).getScore(), i + 1, candidates.get(i - 1).getScore()));
        }
        for (PoiCandidate c : candidates) {
            assertTrue(c.getScore() >= -10.0001 && c.getScore() <= 18.0001,
                    "总分应落在配置有界区间 [-10, +18]: %s=%.2f".formatted(c.getName(), c.getScore()));
        }
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String abbreviate(String text, int maxWidth) {
        if (text == null) {
            return "-";
        }
        return text.length() <= maxWidth ? text : text.substring(0, maxWidth - 1) + "…";
    }

    private static String nullToDash(String text) {
        return text == null ? "-" : text;
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
