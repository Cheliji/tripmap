package com.tripmap.tool.cluster;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.cluster.config.ClusterProperties;
import com.tripmap.tool.cluster.dto.ClusterInput;
import com.tripmap.tool.cluster.dto.ClusterOutput;
import com.tripmap.tool.cluster.dto.DayCluster;
import com.tripmap.tool.poi.PoiCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间聚类工具单测（任务 2.5.6）：构造已知地理分布，验证锚定/平衡/排序正确性。
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
        System.out.printf("  行程天数 k = %d，候选 %d 个：%n", days, candidates.size());
        System.out.printf("  %-6s %-14s %-12s %-12s %s%n", "id", "名称", "纬度", "经度", "必去");
        for (PoiCandidate c : candidates) {
            System.out.printf("  %-6d %-14s %-12s %-12s %s%n", c.getId(), c.getName(),
                    c.getLatitude(), c.getLongitude(), c.isMustVisit() ? "是" : "");
        }
    }

    private void printOutput(ClusterOutput output) {
        System.out.printf("  ---- 聚类结果（种子=%d，迭代 %d 轮，锚组 %d，平衡移动 %d 点，达标=%s）----%n",
                output.getSeed(), output.getIterations(), output.getAnchorGroupCount(),
                output.getMovedPoints(), output.isBalanced() ? "是" : "否");
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

    // ---------- 用例 ----------

    @Test
    @DisplayName("已知两簇分布：西湖 4 点 + 良渚 4 点，k=2 应各归一簇")
    void knownTwoClusters() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
        candidates.addAll(westLake(1));
        candidates.addAll(liangzhu(11));
        printInput("两个地理上明显分离的簇，无必去点（纯 K-Means）", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).seed(42L).build());
        printOutput(output);

        assertEquals(2, output.getClusters().size());
        assertEquals(0, output.getAnchorGroupCount());
        // 每簇 4 点，且西湖 4 点同天、良渚 4 点同天
        for (DayCluster day : output.getClusters()) {
            assertEquals(4, day.getPoints().size());
        }
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
    @DisplayName("固定种子可复现：同输入跑两次结果完全一致")
    void reproducibleWithSeed() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
        candidates.addAll(westLake(1));
        candidates.addAll(liangzhu(11));
        ClusterInput input = ClusterInput.builder().candidates(candidates).days(2).seed(7L).build();

        ClusterOutput first = tool.execute(input);
        ClusterOutput second = tool.execute(input);
        System.out.printf("%n  两次运行逐日成员集合对比：%n");
        for (int i = 0; i < first.getClusters().size(); i++) {
            Set<Long> a = idsOf(first.getClusters().get(i));
            Set<Long> b = idsOf(second.getClusters().get(i));
            System.out.printf("  第 %d 天: 第一次 %s / 第二次 %s%n", i + 1, a, b);
            assertEquals(a, b);
        }
        assertEquals(first.getSeed(), second.getSeed());
        assertEquals(first.getIterations(), second.getIterations());
    }

    @Test
    @DisplayName("必去点锚定：两个远距离必去点各占一天，质心固定不动")
    void mustVisitAnchored() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
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
                .candidates(candidates).days(2).seed(42L).build());
        printOutput(output);

        assertEquals(2, output.getAnchorGroupCount());
        DayCluster westLakeDay = dayOf(output, 1);
        DayCluster liangzhuDay = dayOf(output, 11);
        assertTrue(westLakeDay.isAnchored() && liangzhuDay.isAnchored(), "两个必去点应各自锚定一天");
        assertTrue(westLakeDay.getDaySeq() != liangzhuDay.getDaySeq(), "两个远距离必去点不能同一天");
        // 邻近自由点被必去点"吸"进同簇
        assertEquals(Set.of(1L, 2L, 3L), idsOf(westLakeDay));
        assertEquals(Set.of(11L, 12L, 13L), idsOf(liangzhuDay));
        // 锚定簇质心 = 必去点自身坐标（单点锚组）
        assertEquals(30.2586, westLakeDay.getCentroidLat(), 1e-6);
        assertEquals(120.1517, westLakeDay.getCentroidLng(), 1e-6);
    }

    @Test
    @DisplayName("必去点近邻归并：相距约 1km 的两个必去点归为同组同一天")
    void nearbyMustVisitsMerged() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
        // 断桥残雪 ↔ 平湖秋月 约 1km < 阈值 1500m，应归并
        candidates.add(poi(1, "断桥残雪", 30.2586, 120.1517, true));
        candidates.add(poi(2, "平湖秋月", 30.2534, 120.1432, true));
        candidates.add(poi(3, "苏堤春晓", 30.2385, 120.1340));
        // 良渚簇（k=2 的第二天）
        candidates.addAll(liangzhu(11));
        printInput("两个相邻必去点（约 1km）+ 西湖自由点 + 良渚簇，k=2", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).seed(42L).build());
        printOutput(output);

        assertEquals(1, output.getAnchorGroupCount(), "相邻必去点应归并为 1 个锚组");
        DayCluster westLakeDay = dayOf(output, 1);
        assertEquals(idsOf(westLakeDay), idsOf(dayOf(output, 2)), "两个相邻必去点必须同一天");
        assertTrue(westLakeDay.isAnchored());
        // 锚定簇质心 = 组成员均值
        assertEquals((30.2586 + 30.2534) / 2, westLakeDay.getCentroidLat(), 1e-6);
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
                ClusterInput.builder().candidates(candidates).days(2).seed(42L).build()));
        System.out.printf("  抛出异常: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("平衡校正不强行跨界：西湖6/良渚1 失衡但跨界荒谬 → 保持地理纯度，balanced=false")
    void balanceCorrectionRejectsAbsurdCrossing() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
        candidates.add(poi(1, "断桥残雪", 30.2586, 120.1517));
        candidates.add(poi(2, "平湖秋月", 30.2534, 120.1432));
        candidates.add(poi(3, "曲院风荷", 30.2470, 120.1330));
        candidates.add(poi(4, "苏堤春晓", 30.2385, 120.1340));
        candidates.add(poi(5, "花港观鱼", 30.2330, 120.1400));
        candidates.add(poi(6, "雷峰塔", 30.2312, 120.1489));
        candidates.add(poi(11, "良渚古城遗址", 30.3959, 119.9880));
        printInput("西湖 6 点 + 良渚孤立 1 点，k=2：跨界 20km+ 不成立，宁可不平衡", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).seed(42L).build());
        printOutput(output);

        assertEquals(0, output.getMovedPoints(), "跨界荒谬时不得移动任何点");
        assertTrue(!output.isBalanced(), "接受不平衡并如实标记");
        assertEquals(Set.of(11L), idsOf(dayOf(output, 11)), "良渚簇应只含良渚点（地理纯度）");
        assertEquals(6, dayOf(output, 1).getPoints().size(), "西湖 6 点应完整保留");
    }

    @Test
    @DisplayName("平衡校正正向用例：簇大且边缘点与邻近小簇相邻（≤15km）时移交")
    void balanceCorrectionGenuineCrossing() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
        // 紧凑簇 A：6 点围在 (30.25, 120.14) 周围约 1.5km 内
        candidates.add(poi(1, "A1", 30.2600, 120.1400));
        candidates.add(poi(2, "A2", 30.2550, 120.1450));
        candidates.add(poi(3, "A3", 30.2500, 120.1400));
        candidates.add(poi(4, "A4", 30.2450, 120.1450));
        candidates.add(poi(5, "A5", 30.2500, 120.1350));
        candidates.add(poi(6, "A6", 30.2550, 120.1350));
        // 邻近小簇 B：2 点在 A 西侧约 4km（相邻区域，非远郊）
        candidates.add(poi(11, "B1", 30.2650, 120.1000));
        candidates.add(poi(12, "B2", 30.2600, 120.0950));
        printInput("紧凑 6 点簇 + 相邻 2 点小簇（约 4km），k=2：6/2 → 5/3 合法移交", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).seed(42L).build());
        printOutput(output);

        assertTrue(output.isBalanced(), "相邻区域移交后应达标（6/2 → 5/3，差 2）");
        assertEquals(1, output.getMovedPoints());
        List<Integer> sizes = output.getClusters().stream()
                .map(d -> d.getPoints().size()).sorted().toList();
        assertEquals(List.of(3, 5), sizes);
        // B 簇吸收一个 A 点后：B 两点同簇 + 吸收的 A 点
        DayCluster bDay = dayOf(output, 11);
        assertTrue(idsOf(bDay).contains(11L) && idsOf(bDay).contains(12L), "B 两点必须同簇");
        assertEquals(3, bDay.getPoints().size(), "B 簇应吸收一个 A 点后为 3 点");
    }

    @Test
    @DisplayName("簇→天排序：西→中→东线性三簇按贪心最近邻编号")
    void dayOrderingByCentroidProximity() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
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
                .candidates(candidates).days(3).seed(42L).build());
        printOutput(output);

        // 起点 = 首个锚定簇（西溪），最近邻链：西溪 → 西湖（~8km）→ 钱江新城（~7km）
        assertEquals(1, dayOf(output, 1).getDaySeq(), "西溪簇应为第 1 天（排序起点）");
        assertEquals(2, dayOf(output, 3).getDaySeq(), "西湖簇居中，应为第 2 天");
        assertEquals(3, dayOf(output, 5).getDaySeq(), "钱江新城簇应为第 3 天");
    }

    @Test
    @DisplayName("无必去点退化纯 K-Means：anchorGroupCount=0，结果正常")
    void noMustVisitPureKMeans() {
        List<PoiCandidate> candidates = new java.util.ArrayList<>();
        candidates.addAll(westLake(1));
        candidates.addAll(liangzhu(11));
        printInput("无必去点（纯 K-Means，锚定/归并整体跳过）", candidates, 2);

        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(candidates).days(2).seed(42L).build());
        printOutput(output);

        assertEquals(0, output.getAnchorGroupCount());
        assertTrue(output.getClusters().stream().noneMatch(DayCluster::isAnchored));
        assertEquals(8, output.getClusters().stream().mapToInt(d -> d.getPoints().size()).sum());
    }

    @Test
    @DisplayName("候选数少于天数 → 规划不可行（300002）")
    void candidatesFewerThanDays() {
        List<PoiCandidate> candidates = List.of(
                poi(1, "断桥残雪", 30.2586, 120.1517),
                poi(2, "雷峰塔", 30.2312, 120.1489));
        printInput("只有 2 个候选但行程 3 天", candidates, 3);

        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(candidates).days(3).seed(42L).build()));
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
                ClusterInput.builder().candidates(noCoord).days(2).seed(42L).build()));
        System.out.printf("  坐标缺失 → code=%d, message=%s%n", e1.getCode(), e1.getMessage());
        assertEquals(ResultCode.PARAM_ILLEGAL.getCode(), e1.getCode());

        BizException e2 = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(westLake(1)).days(null).build()));
        System.out.printf("  缺 days  → code=%d, message=%s%n", e2.getCode(), e2.getMessage());
        assertEquals(ResultCode.PARAM_MISSING.getCode(), e2.getCode());
    }
}
