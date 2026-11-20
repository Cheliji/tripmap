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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间聚类真实数据验证（任务 2.5 补充，2026-11-19）：
 * 从库里 3051 条杭州 POI 中按 id 分桶随机抽样 30 条（id 20~3070 等分 30 桶、
 * 每桶一条，保证 id 不邻近），快照成本测试常量（运行时不依赖数据库）。
 *
 * <p>样本覆盖：西湖/拱墅（市中心）、余杭/临安（西北）、萧山/钱塘（东南）、
 * 富阳（西南）、淳安/建德/桐庐（远郊 60km+）——天然的地理多簇分布，
 * 同时覆盖我们讨论过的全部场景：纯 K-Means / 远距离锚定 / 近邻必去归并 /
 * 锚组超天数 / 平衡校正 / 簇→天排序 / k=1 退化 / 候选不足。</p>
 *
 * <p>按 AGENTS.md 定规：打印输入分布与逐日聚类结果（含行政区），
 * console 可直接人工核对的地理合理性。</p>
 */
class SpatialClusterRealDataTest {

    private final ClusterProperties properties = new ClusterProperties();
    private final SpatialClusterTool tool = new SpatialClusterTool(properties);

    // ---------- 真实数据快照（2026-11-19 自 tripmap.poi 抽样，id 分桶分散） ----------

    /** {id, 名称, 区县, 经度, 纬度} */
    private static final String[][] RAW = {
            {"106", "栢树庙", "余杭区", "120.017577", "30.361351"},
            {"129", "梅鹤轩", "西湖区", "120.145519", "30.251672"},
            {"248", "杭州动物园", "西湖区", "120.133159", "30.212281"},
            {"363", "西溪国家湿地公园东区", "西湖区", "120.068053", "30.269035"},
            {"441", "那嘉广场(新世界·丽晶国际中心店)", "萧山区", "120.246305", "30.241744"},
            {"617", "杭州野生动物世界-白狮", "富阳区", "119.985023", "30.149218"},
            {"635", "MAMMUT猛犸象", "钱塘区", "120.462825", "30.298625"},
            {"750", "孝廉广场", "上城区", "120.236224", "30.383108"},
            {"880", "基督教堂", "余杭区", "119.828468", "30.402816"},
            {"973", "通天飞瀑风景区", "富阳区", "119.629190", "30.001471"},
            {"1039", "海瑞纪念馆", "淳安县", "118.983429", "29.605797"},
            {"1214", "永福寺", "西湖区", "120.097524", "30.238978"},
            {"1339", "法海极乐禅寺", "萧山区", "120.424145", "30.171730"},
            {"1378", "杭州市协同基督教堂", "滨江区", "120.218909", "30.214534"},
            {"1527", "回龙庙", "建德市", "119.513296", "29.466830"},
            {"1602", "北塘河绿道观景台", "萧山区", "120.270611", "30.190594"},
            {"1690", "童趣屋游乐中心", "富阳区", "120.098531", "30.046931"},
            {"1793", "兔子王国(临平万达广场店)", "临平区", "120.284375", "30.432675"},
            {"1900", "中国印学博物馆", "西湖区", "120.139545", "30.251341"},
            {"2001", "大运河紫檀·匠心广场", "拱墅区", "120.136625", "30.313825"},
            {"2070", "画英雄Herochrome", "拱墅区", "120.165015", "30.354803"},
            {"2251", "杭州稽古金石艺术馆", "富阳区", "119.978892", "30.156036"},
            {"2300", "浙江大学(紫金港校区)紫金港国际会议中心", "西湖区", "120.090230", "30.303834"},
            {"2457", "夏塘村文化礼堂", "桐庐县", "119.429764", "29.791964"},
            {"2541", "径山美术馆", "余杭区", "119.832520", "30.400945"},
            {"2601", "麦家理想谷", "西湖区", "120.076309", "30.279131"},
            {"2738", "亚朵竹居流动图书馆(杭州钱塘理工大学下沙校区)", "钱塘区", "120.355601", "30.306020"},
            {"2818", "银泰百货(杭州中大店)", "拱墅区", "120.175887", "30.327277"},
            {"2874", "宝龙广场(青山湖店)", "临安区", "119.827541", "30.257908"},
            {"3028", "汇禾领府美食街(步行街店)", "西湖区", "120.071767", "30.322611"},
    };

    private static final List<PoiCandidate> REAL_POIS = java.util.Arrays.stream(RAW)
            .map(row -> PoiCandidate.builder()
                    .id(Long.parseLong(row[0]))
                    .name(row[1])
                    .district(row[2])
                    .longitude(new BigDecimal(row[3]))
                    .latitude(new BigDecimal(row[4]))
                    .mustVisit(false)
                    .build())
            .toList();

    private static final Map<Long, PoiCandidate> BY_ID =
            REAL_POIS.stream().collect(Collectors.toMap(PoiCandidate::getId, Function.identity()));

    /** 把指定 id 的候选重建为必去点（其余原样拷贝） */
    private List<PoiCandidate> withMustVisits(Set<Long> mustIds) {
        return REAL_POIS.stream()
                .map(p -> PoiCandidate.builder()
                        .id(p.getId()).name(p.getName()).district(p.getDistrict())
                        .longitude(p.getLongitude()).latitude(p.getLatitude())
                        .mustVisit(mustIds.contains(p.getId()))
                        .build())
                .toList();
    }

    // ---------- 人读日志辅助 ----------

    private void printInput(String scenario, int days, Set<Long> mustIds) {
        System.out.println("""
                
                ========== 真实数据场景：%s ==========""".formatted(scenario));
        System.out.printf("  k=%d，必去 id=%s%n", days, mustIds.isEmpty() ? "（无）" : mustIds);
        System.out.printf("  %-6s %-14s %-6s %-12s %-12s%n", "id", "名称", "区县", "经度", "纬度");
        for (PoiCandidate c : REAL_POIS) {
            System.out.printf("  %-6d %-14s %-6s %-12s %-12s%s%n", c.getId(), c.getName(),
                    c.getDistrict(), c.getLongitude(), c.getLatitude(),
                    mustIds.contains(c.getId()) ? "  ← 必去" : "");
        }
    }

    private void printOutput(ClusterOutput output) {
        System.out.printf("  ---- 聚类结果（种子=%d，迭代 %d 轮，锚组 %d，平衡移动 %d 点，达标=%s）----%n",
                output.getSeed(), output.getIterations(), output.getAnchorGroupCount(),
                output.getMovedPoints(), output.isBalanced() ? "是" : "否");
        for (DayCluster day : output.getClusters()) {
            System.out.printf("  第 %d 天%s 质心(%.4f, %.4f) %2d 点：%s%n",
                    day.getDaySeq(), day.isAnchored() ? "[锚]" : "[自]",
                    day.getCentroidLat(), day.getCentroidLng(), day.getPoints().size(),
                    day.getPoints().stream()
                            .map(p -> p.getName() + "[" + p.getDistrict() + "]")
                            .collect(Collectors.joining("、")));
        }
    }

    /** 不变量校验：30 个候选每个恰好出现一次（分区完整性） */
    private void assertPartition(ClusterOutput output) {
        List<Long> all = output.getClusters().stream()
                .flatMap(d -> d.getPoints().stream()).map(PoiCandidate::getId).toList();
        assertEquals(REAL_POIS.size(), all.size(), "每个候选应恰好分配一次");
        assertEquals(REAL_POIS.size(), new HashSet<>(all).size(), "不允许重复分配");
    }

    private DayCluster dayOf(ClusterOutput output, long poiId) {
        return output.getClusters().stream()
                .filter(d -> d.getPoints().stream().anyMatch(p -> p.getId() == poiId))
                .findFirst().orElseThrow();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("纯 K-Means k=4：真实 30 点应聚成市中心/西北/东南/远郊四片")
    void realDataPureKMeans() {
        printInput("无必去点，k=4（西湖拱墅市中心 / 余杭临安西北 / 萧山钱塘东南 / 富阳远郊）", 4, Set.of());
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(4).seed(42L).build());
        printOutput(output);

        assertEquals(0, output.getAnchorGroupCount());
        assertPartition(output);
        // 地理合理性抽查：淳安/建德/桐庐（远郊 60km+）三个点应同簇（离其他点都极远）
        DayCluster farDay = dayOf(output, 1039);
        assertEquals(dayOf(output, 1527).getDaySeq(), farDay.getDaySeq(), "建德应与淳安同簇");
        assertEquals(dayOf(output, 2457).getDaySeq(), farDay.getDaySeq(), "桐庐应与淳安同簇");
        // 钱塘区两点（下沙 2738 与大江东 635，相距约 11km）应同簇
        assertEquals(dayOf(output, 635).getDaySeq(), dayOf(output, 2738).getDaySeq(),
                "钱塘区两点应同簇");
    }

    @Test
    @DisplayName("纯 K-Means k=5 两次运行完全一致（真实数据种子可复现）")
    void realDataReproducible() {
        ClusterInput input = ClusterInput.builder().candidates(REAL_POIS).days(5).seed(2026L).build();
        ClusterOutput a = tool.execute(input);
        ClusterOutput b = tool.execute(input);
        System.out.printf("%n  真实数据 k=5 两次运行对比（种子 2026）：%n");
        for (int i = 0; i < a.getClusters().size(); i++) {
            Set<Long> sa = a.getClusters().get(i).getPoints().stream().map(PoiCandidate::getId)
                    .collect(Collectors.toSet());
            Set<Long> sb = b.getClusters().get(i).getPoints().stream().map(PoiCandidate::getId)
                    .collect(Collectors.toSet());
            System.out.printf("  第 %d 天: %s %s%n", i + 1, sa, sa.equals(sb) ? "= 第二次 ✓" : "≠ 第二次 ✗");
            assertEquals(sa, sb);
        }
        assertPartition(a);
    }

    @Test
    @DisplayName("远距离必去点锚定：西溪(363) + 野生动物世界(617)，k=5 各占一天且吸附邻近点")
    void realDataDistantAnchors() {
        Set<Long> must = Set.of(363L, 617L);
        printInput("必去=西溪湿地 + 杭州野生动物世界（相距约 15km），k=5", 5, must);
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(withMustVisits(must)).days(5).seed(42L).build());
        printOutput(output);

        assertEquals(2, output.getAnchorGroupCount());
        assertPartition(output);
        DayCluster xixi = dayOf(output, 363);
        DayCluster safari = dayOf(output, 617);
        assertTrue(xixi.isAnchored() && safari.isAnchored(), "两个必去点各自锚定一天");
        assertTrue(xixi.getDaySeq() != safari.getDaySeq(), "两个远距离必去点不能同一天");
        // 排序起点 = 首个锚定簇（西溪是候选列表中首个必去点）
        assertEquals(1, xixi.getDaySeq(), "排序起点 = 首个锚定簇");
        // 锚定吸附：永福寺/麦家理想谷离西溪 3~5km，应进西溪簇而非野生动物世界簇
        assertEquals(xixi.getDaySeq(), dayOf(output, 1214).getDaySeq(), "永福寺应被西溪吸附");
        assertEquals(xixi.getDaySeq(), dayOf(output, 2601).getDaySeq(), "麦家理想谷应被西溪吸附");
        // 富阳侧的点应进野生动物世界簇
        assertEquals(safari.getDaySeq(), dayOf(output, 2251).getDaySeq(), "稽古金石艺术馆应被野生动物世界吸附");
    }

    @Test
    @DisplayName("近邻必去点归并：梅鹤轩(129) 与 印学博物馆(1900) 相距约 570m，同组同一天")
    void realDataNearbyMustVisitMerged() {
        Set<Long> must = Set.of(129L, 1900L);
        printInput("必去=梅鹤轩 + 中国印学博物馆（相距约 570m，孤山一带），k=4", 4, must);
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(withMustVisits(must)).days(4).seed(42L).build());
        printOutput(output);

        assertEquals(1, output.getAnchorGroupCount(), "两个相邻必去点应归并为 1 个锚组");
        assertPartition(output);
        DayCluster gushan = dayOf(output, 129);
        assertEquals(gushan.getDaySeq(), dayOf(output, 1900).getDaySeq(), "两个相邻必去点必须同一天");
        assertTrue(gushan.isAnchored());
        // 组质心 = 两点均值
        assertEquals((30.251672 + 30.251341) / 2, gushan.getCentroidLat(), 1e-6);
        // 孤山位于西湖核心，邻近的西湖点应被吸附：杭州动物园(248) 距孤山约 4.5km
        assertEquals(gushan.getDaySeq(), dayOf(output, 248).getDaySeq(), "杭州动物园应被孤山锚组吸附");
    }

    @Test
    @DisplayName("锚组超天数：4 个互不相邻必去点 + k=3 → 规划不可行（300002）")
    void realDataTooManyAnchorGroups() {
        // 四选必去点两两相距均 > 1500m：西溪(363)、野生动物世界(617)、淳安海瑞纪念馆(1039)、钱塘下沙(2738)
        Set<Long> must = Set.of(363L, 617L, 1039L, 2738L);
        printInput("必去=西溪/野生动物世界/淳安/下沙（互不相邻），但行程只有 3 天", 3, must);
        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(withMustVisits(must)).days(3).seed(42L).build()));
        System.out.printf("  抛出异常: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("平衡校正：k=8 时远郊三点仍独占小簇，校正后大小差 ≤ 2 或明确标记未达标")
    void realDataBalanceCorrection() {
        printInput("k=8（30 点分 8 天，远郊三点天然小簇，考验平衡校正）", 8, Set.of());
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(8).seed(42L).build());
        printOutput(output);

        assertPartition(output);
        // 真实数据回归：银泰百货（拱墅区商场）绝不应与淳安（60km+ 远郊）同簇——
        // 2026-11-19 验证曾发现平衡校正硬凑出"拱墅商场 + 淳安景点同一天"
        assertTrue(dayOf(output, 2818).getDaySeq() != dayOf(output, 1039).getDaySeq(),
                "银泰百货[拱墅]不得与淳安海瑞纪念馆同簇");
        List<Integer> sizes = output.getClusters().stream()
                .map(d -> d.getPoints().size()).sorted().toList();
        System.out.printf("  簇大小分布（升序）: %s（balanced=%s，移动 %d 点）%n",
                sizes, output.isBalanced(), output.getMovedPoints());
        if (output.isBalanced()) {
            assertTrue(sizes.get(sizes.size() - 1) - sizes.get(0) <= properties.getBalanceThreshold(),
                    "标记达标时大小差必须 ≤ 阈值");
        } else {
            // 未达标是合法结果：地理纯度优先，簇偏小由上游补候选/替补解决（§6.4）
            System.out.println("  注：balanced=false 为合法结果——无可真实跨界的移动，保持地理纯度");
        }
    }

    @Test
    @DisplayName("簇→天排序：贪心链性质校验（相邻天质心最近）")
    void realDataDayOrderingGreedyChain() {
        printInput("k=5 排序校验：每个后续天必须是当时质心最近者", 5, Set.of());
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(5).seed(42L).build());
        printOutput(output);
        assertPartition(output);

        // 贪心链性质：day(i+1) 的质心是所有未排簇中离 day(i) 最近的
        List<DayCluster> days = new ArrayList<>(output.getClusters());
        for (int i = 1; i < days.size(); i++) {
            DayCluster prev = days.get(i - 1);
            double actual = centroidDist(prev, days.get(i));
            for (int j = i + 1; j < days.size(); j++) {
                double alternative = centroidDist(prev, days.get(j));
                assertTrue(actual <= alternative + 1e-9,
                        String.format("第 %d 天应选质心最近者：实际 %.1fkm vs 候选第 %d 天 %.1fkm",
                                i + 1, actual / 1000, j + 1, alternative / 1000));
            }
            System.out.printf("  第 %d 天 → 第 %d 天质心距离 %.1f km（最近者 ✓）%n",
                    i, i + 1, actual / 1000);
        }
    }

    @Test
    @DisplayName("k=1 退化：全部 30 点一天，锚定/排序/平衡全部退化正确")
    void realDataSingleDay() {
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(1).seed(42L).build());
        System.out.printf("%n  k=1：簇数=%d，点数=%d，平衡=%s%n",
                output.getClusters().size(), output.getClusters().get(0).getPoints().size(),
                output.isBalanced());
        assertEquals(1, output.getClusters().size());
        assertEquals(REAL_POIS.size(), output.getClusters().get(0).getPoints().size());
        assertEquals(1, output.getClusters().get(0).getDaySeq());
        assertPartition(output);
    }

    @Test
    @DisplayName("候选少于天数：30 点 + k=31 → 规划不可行（300002）")
    void realDataCandidatesFewerThanDays() {
        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(REAL_POIS).days(31).seed(42L).build()));
        System.out.printf("%n  30 点 + k=31 → code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
    }

    /** 两天簇质心的 haversine 距离（米） */
    private double centroidDist(DayCluster a, DayCluster b) {
        double radLat1 = Math.toRadians(a.getCentroidLat());
        double radLat2 = Math.toRadians(b.getCentroidLat());
        double dLat = radLat2 - radLat1;
        double dLon = Math.toRadians(b.getCentroidLng() - a.getCentroidLng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_008.8 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
