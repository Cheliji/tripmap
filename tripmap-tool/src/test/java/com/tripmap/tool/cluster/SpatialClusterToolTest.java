package com.tripmap.tool.cluster;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.cluster.config.ClusterProperties;
import com.tripmap.tool.cluster.dto.ClusterInput;
import com.tripmap.tool.cluster.dto.ClusterOutput;
import com.tripmap.tool.cluster.dto.DayCluster;
import com.tripmap.tool.cluster.dto.ForbiddenPair;
import com.tripmap.tool.poi.PoiCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间聚类工具单测（任务 2.5.11，v1.4 CP-SAT 约束划分重写）：构造已知地理分布，
 * 验证硬约束（每日 1~7 点、簇直径）、锚定归并、禁配回切、排序与确定性。
 *
 * <p>测试坐标取杭州真实地理关系（GCJ-02 近似值）：</p>
 * <ul>
 *   <li>西湖环线簇：断桥残雪、平湖秋月、苏堤春晓、雷峰塔（互相 1~3 km）；</li>
 *   <li>良渚簇：良渚古城遗址、良渚博物院一带（距西湖约 18 km）；</li>
 *   <li>西溪簇：西溪湿地一带（距西湖约 8 km）。</li>
 * </ul>
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入分布、聚类结果表，console 可直接 review。</p>
 */
class SpatialClusterToolTest {

    private final ClusterProperties properties = new ClusterProperties();
    private final SpatialClusterTool tool = new SpatialClusterTool(properties);

    // ---------- 人读日志辅助 ----------

    private void printInput(String scenario, List<PoiCandidate> candidates, int days) {
        System.out.println("""
                
                ========== 场景：%s ==========""".formatted(scenario));
        System.out.printf("  行程天数 k = %d，候选 %d 个（每日点数 [%d,%d]，簇直径 ≤ %.0fkm）：%n",
                days, candidates.size(), properties.getMinPoisPerDay(),
                properties.getMaxPoisPerDay(), properties.getMaxClusterDiameterMeters() / 1000);
        System.out.printf("  %-6s %-14s %-12s %-12s %s%n", "id", "名称", "纬度", "经度", "必去");
        for (PoiCandidate c : candidates) {
            System.out.printf("  %-6d %-14s %-12s %-12s %s%n", c.getId(), c.getName(),
                    c.getLatitude(), c.getLongitude(), c.isMustVisit() ? "是" : "");
        }
    }

    private void printOutput(ClusterOutput output) {
        System.out.printf("  ---- 聚类结果（锚组 %d）----%n", output.getAnchorGroupCount());
        for (DayCluster day : output.getClusters()) {
            System.out.printf("  第 %d 天%s 质心(%.4f, %.4f) %d 点: %s%n",
                    day.getDaySeq(), day.isAnchored() ? "[锚定]" : "[自由]",
                    day.getCentroidLat(), day.getCentroidLng(), day.getPoints().size(),
                    day.getPoints().stream().map(PoiCandidate::getName)
                            .collect(Collectors.joining("、")));
        }
    }

    // ---------- 造数辅助 ----------

    private PoiCandidate poi(long id, String name, double lat, double lng) {
        return poi(id, name, lat, lng, false);
    }

    private PoiCandidate poi(long id, String name, double lat, double lng, boolean mustVisit) {
        return PoiCandidate.builder()
                .id(id).name(name)
                .latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng))
                .mustVisit(mustVisit)
                .build();
    }

    /** 西湖环线 4 点（互相 1~3 km） */
    private List<PoiCandidate> westLake(long idBase) {
        return List.of(
                poi(idBase, "断桥残雪", 30.2586, 120.1517),
                poi(idBase + 1, "平湖秋月", 30.2534, 120.1432),
                poi(idBase + 2, "苏堤春晓", 30.2385, 120.1340),
                poi(idBase + 3, "雷峰塔", 30.2312, 120.1489));
    }

    /** 良渚 4 点（距西湖约 18 km） */
    private List<PoiCandidate> liangzhu(long idBase) {
        return List.of(
                poi(idBase, "良渚古城遗址", 30.3959, 119.9880),
                poi(idBase + 1, "良渚博物院", 30.3886, 119.9955),
                poi(idBase + 2, "良渚文化村", 30.3800, 120.0010),
                poi(idBase + 3, "瑶山遗址", 30.4100, 119.9750));
    }

    private DayCluster dayOf(ClusterOutput output, long poiId) {
        return output.getClusters().stream()
                .filter(d -> d.getPoints().stream().anyMatch(p -> p.getId() == poiId))
                .findFirst().orElseThrow();
    }

    private Set<Long> idsOf(DayCluster day) {
        return day.getPoints().stream().map(PoiCandidate::getId).collect(Collectors.toSet());
    }

    /** 断言每天点数都在 [min,max] 硬约束内 */
    private void assertSizeWithinBounds(ClusterOutput output) {
        for (DayCluster day : output.getClusters()) {
            int size = day.getPoints().size();
            assertTrue(size >= properties.getMinPoisPerDay()
                            && size <= properties.getMaxPoisPerDay(),
                    String.format("第 %d 天 %d 点，超出硬约束 [%d,%d]",
                            day.getDaySeq(), size,
                            properties.getMinPoisPerDay(), properties.getMaxPoisPerDay()));
        }
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("已知两簇分布：西湖 4 点 + 良渚 4 点，k=2 应各归一簇")
    void knownTwoClusters() {
        List<PoiCandidate> candidates = new ArrayList<>();
        candidates.addAll(westLake(1));
        candidates.addAll(liangzhu(11));
        printInput("两个地理上明显分离的簇，无必去点（纯约束划分）", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).build());
        printOutput(output);

        assertEquals(2, output.getClusters().size());
        assertEquals(0, output.getAnchorGroupCount());
        assertSizeWithinBounds(output);
        // 西湖 4 点同天、良渚 4 点同天（直径约束下不可能跨界）
        Set<Long> westLakeIds = Set.of(1L, 2L, 3L, 4L);
        Set<Long> liangzhuIds = Set.of(11L, 12L, 13L, 14L);
        Set<Long> day1 = idsOf(output.getClusters().get(0));
        Set<Long> day2 = idsOf(output.getClusters().get(1));
        assertTrue(day1.equals(westLakeIds) || day1.equals(liangzhuIds),
                "第 1 天应为完整地理簇，实际 " + day1);
        assertTrue(day2.equals(westLakeIds) || day2.equals(liangzhuIds),
                "第 2 天应为完整地理簇，实际 " + day2);
    }

    @Test
    @DisplayName("每日 1~7 硬约束：10 点 k=3，每天点数必须落在 [1,7]")
    void sizeHardConstraint() {
        // 3 个紧凑小簇：4/4/2，最自然是 4/4/2 或 4/3/3，均满足 ≤7
        List<PoiCandidate> candidates = new ArrayList<>();
        candidates.addAll(westLake(1));
        candidates.addAll(liangzhu(11));
        candidates.add(poi(21, "西溪湿地", 30.2740, 120.0630));
        candidates.add(poi(22, "西溪洪园", 30.2680, 120.0580));
        printInput("西湖 4 + 良渚 4 + 西溪 2，k=3", candidates, 3);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(3).build());
        printOutput(output);

        assertSizeWithinBounds(output);
        assertEquals(10, output.getClusters().stream().mapToInt(d -> d.getPoints().size()).sum());
    }

    @Test
    @DisplayName("确定性：同输入跑两次结果完全一致（固定参数 + 单 worker，无需种子）")
    void deterministic() {
        List<PoiCandidate> candidates = new ArrayList<>();
        candidates.addAll(westLake(1));
        candidates.addAll(liangzhu(11));
        ClusterInput input = ClusterInput.builder().candidates(candidates).days(2).build();

        ClusterOutput first = tool.execute(input);
        ClusterOutput second = tool.execute(input);
        System.out.printf("%n  两次运行逐日成员集合对比：%n");
        for (int i = 0; i < first.getClusters().size(); i++) {
            Set<Long> a = idsOf(first.getClusters().get(i));
            Set<Long> b = idsOf(second.getClusters().get(i));
            System.out.printf("  第 %d 天: 第一次 %s / 第二次 %s%n", i + 1, a, b);
            assertEquals(a, b, "同输入必须同输出（确定性）");
        }
    }

    @Test
    @DisplayName("必去点锚定：两个远距离必去点各占一天，组内自由点被吸入同簇")
    void mustVisitAnchored() {
        List<PoiCandidate> candidates = new ArrayList<>();
        // 西湖簇 3 个自由点 + 1 个必去（断桥残雪）
        candidates.add(poi(1, "断桥残雪", 30.2586, 120.1517, true));
        candidates.add(poi(2, "苏堤春晓", 30.2385, 120.1340));
        candidates.add(poi(3, "雷峰塔", 30.2312, 120.1489));
        // 良渚簇 3 个自由点 + 1 个必去（良渚古城遗址）
        candidates.add(poi(11, "良渚古城遗址", 30.3959, 119.9880, true));
        candidates.add(poi(12, "良渚博物院", 30.3886, 119.9955));
        candidates.add(poi(13, "良渚文化村", 30.3800, 120.0010));
        printInput("2 个相距 18km 的必去点 + 各 3 个邻近自由点，k=2", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).build());
        printOutput(output);

        assertEquals(2, output.getAnchorGroupCount());
        DayCluster westLakeDay = dayOf(output, 1);
        DayCluster liangzhuDay = dayOf(output, 11);
        assertTrue(westLakeDay.isAnchored() && liangzhuDay.isAnchored(), "两个必去点应各自锚定一天");
        assertTrue(westLakeDay.getDaySeq() != liangzhuDay.getDaySeq(), "两个远距离必去点不能同一天");
        // 邻近自由点被必去点"吸"进同簇
        assertEquals(Set.of(1L, 2L, 3L), idsOf(westLakeDay));
        assertEquals(Set.of(11L, 12L, 13L), idsOf(liangzhuDay));
    }

    @Test
    @DisplayName("必去点近邻归并：相距约 1km 的两个必去点归为同组同一天")
    void nearbyMustVisitsMerged() {
        List<PoiCandidate> candidates = new ArrayList<>();
        // 断桥残雪 ↔ 平湖秋月 约 1km < 阈值 1500m，应归并
        candidates.add(poi(1, "断桥残雪", 30.2586, 120.1517, true));
        candidates.add(poi(2, "平湖秋月", 30.2534, 120.1432, true));
        candidates.add(poi(3, "苏堤春晓", 30.2385, 120.1340));
        // 良渚簇（k=2 的第二天）
        candidates.addAll(liangzhu(11));
        printInput("两个相邻必去点（约 1km）+ 西湖自由点 + 良渚簇，k=2", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).build());
        printOutput(output);

        assertEquals(1, output.getAnchorGroupCount(), "相邻必去点应归并为 1 个锚组");
        DayCluster westLakeDay = dayOf(output, 1);
        assertEquals(idsOf(westLakeDay), idsOf(dayOf(output, 2)), "两个相邻必去点必须同一天");
        assertTrue(westLakeDay.isAnchored());
    }

    @Test
    @DisplayName("必去锚组数超过天数 → 规划不可行（300002）")
    void tooManyAnchorGroups() {
        List<PoiCandidate> candidates = List.of(
                poi(1, "断桥残雪", 30.2586, 120.1517, true),
                poi(11, "良渚古城遗址", 30.3959, 119.9880, true),
                poi(21, "西溪湿地", 30.2740, 120.0630, true));
        printInput("3 个互不相邻的必去点，但行程只有 2 天", candidates, 2);

        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(candidates).days(2).build()));
        System.out.printf("  抛出异常: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("簇直径硬约束：西湖 6 点 + 良渚孤点，k=2 → 良渚独占一天（单点远郊日合法）")
    void diameterConstraintSinglePointDay() {
        List<PoiCandidate> candidates = new ArrayList<>();
        candidates.add(poi(1, "断桥残雪", 30.2586, 120.1517));
        candidates.add(poi(2, "平湖秋月", 30.2534, 120.1432));
        candidates.add(poi(3, "曲院风荷", 30.2470, 120.1330));
        candidates.add(poi(4, "苏堤春晓", 30.2385, 120.1340));
        candidates.add(poi(5, "花港观鱼", 30.2330, 120.1400));
        candidates.add(poi(6, "雷峰塔", 30.2312, 120.1489));
        candidates.add(poi(11, "良渚古城遗址", 30.3959, 119.9880));
        printInput("西湖 6 点 + 良渚孤立 1 点（相距约 18km > 直径 15km），k=2", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).build());
        printOutput(output);

        assertSizeWithinBounds(output);
        // 直径约束下良渚点无法与任何西湖点同天 → 独占一天（合法单点远郊日）
        assertEquals(Set.of(11L), idsOf(dayOf(output, 11)), "良渚孤点应独占一天（单点远郊日）");
        assertEquals(6, dayOf(output, 1).getPoints().size(), "西湖 6 点应完整保留（未超 7 上限）");
    }

    @Test
    @DisplayName("禁配回切：西湖(1,3)被禁配后分天，k=3 装得下（通勤超时点对的模型侧）")
    void forbiddenPairSeparated() {
        List<PoiCandidate> candidates = new ArrayList<>();
        candidates.addAll(westLake(1)); // 1 断桥残雪、2 平湖秋月、3 苏堤春晓、4 雷峰塔
        candidates.addAll(liangzhu(11)); // 11~14 良渚（距西湖约 18km）
        candidates.add(poi(21, "西溪湿地", 30.2740, 120.0630)); // 第三天
        printInput("西湖 4 + 良渚 4 + 西溪 1，k=3，禁配(1,3)：断桥残雪与苏堤春晓不得同天", candidates, 3);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(3)
                .forbiddenPairs(List.of(ForbiddenPair.builder()
                        .firstPoiId(1L).secondPoiId(3L).build()))
                .build());
        printOutput(output);

        assertSizeWithinBounds(output);
        assertTrue(dayOf(output, 1).getDaySeq() != dayOf(output, 3).getDaySeq(),
                "禁配的两个 POI 必须分天（回切环生效）");
    }

    @Test
    @DisplayName("簇→天排序：西→中→东线性三簇按贪心最近邻编号")
    void dayOrderingByCentroidProximity() {
        List<PoiCandidate> candidates = new ArrayList<>();
        // 西簇（西溪，约 120.06）：含必去点钉住排序起点
        candidates.add(poi(1, "西溪湿地", 30.2740, 120.0630, true));
        candidates.add(poi(2, "西溪洪园", 30.2680, 120.0580));
        // 中簇（西湖，约 120.14）
        candidates.add(poi(3, "断桥残雪", 30.2586, 120.1517));
        candidates.add(poi(4, "雷峰塔", 30.2312, 120.1489));
        // 东簇（钱江新城，约 120.21）：含必去点
        candidates.add(poi(5, "城市阳台", 30.2440, 120.2080, true));
        candidates.add(poi(6, "钱江新城市民中心", 30.2480, 120.2050));
        printInput("西溪(西) / 西湖(中) / 钱江新城(东) 三簇，必去点钉住西、东两端，k=3", candidates, 3);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(3).build());
        printOutput(output);

        assertSizeWithinBounds(output);
        // 起点 = 首个锚定簇（西溪），最近邻链：西溪 → 西湖（~8km）→ 钱江新城（~7km）
        assertEquals(1, dayOf(output, 1).getDaySeq(), "西溪簇应为第 1 天（排序起点）");
        assertEquals(2, dayOf(output, 3).getDaySeq(), "西湖簇居中，应为第 2 天");
        assertEquals(3, dayOf(output, 5).getDaySeq(), "钱江新城簇应为第 3 天");
    }

    @Test
    @DisplayName("候选数少于天数 → 规划不可行（300002）")
    void candidatesFewerThanDays() {
        List<PoiCandidate> candidates = List.of(
                poi(1, "断桥残雪", 30.2586, 120.1517),
                poi(2, "雷峰塔", 30.2312, 120.1489));
        printInput("只有 2 个候选但行程 3 天", candidates, 3);

        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(candidates).days(3).build()));
        System.out.printf("  抛出异常: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("参数校验：坐标缺失 → 100003；缺 days → 100002")
    void paramValidation() {
        List<PoiCandidate> noCoord = List.of(
                PoiCandidate.builder().id(1L).name("无坐标景点").build(),
                poi(2, "雷峰塔", 30.2312, 120.1489));
        System.out.println("\n  ========== 场景：参数校验 ==========");
        BizException e1 = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(noCoord).days(2).build()));
        System.out.printf("  坐标缺失 → code=%d, message=%s%n", e1.getCode(), e1.getMessage());
        assertEquals(ResultCode.PARAM_ILLEGAL.getCode(), e1.getCode());

        BizException e2 = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(westLake(1)).days(null).build()));
        System.out.printf("  缺 days  → code=%d, message=%s%n", e2.getCode(), e2.getMessage());
        assertEquals(ResultCode.PARAM_MISSING.getCode(), e2.getCode());
    }
}
