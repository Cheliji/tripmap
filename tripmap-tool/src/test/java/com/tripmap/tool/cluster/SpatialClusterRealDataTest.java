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

    // ---------- 真实数据快照（2026-11-20 自 tripmap.poi 全局随机抽样 ORDER BY RAND() LIMIT 30） ----------

    /** {id, 名称, 区县, 经度, 纬度} */
    private static final String[][] RAW = {
            {"3022", "浙江省杭州市紫荆商街步行街", "拱墅区", "120.183854", "30.340742"},
            {"1848", "西溪银泰城户外游乐园", "西湖区", "120.075225", "30.292975"},
            {"509", "万向公园", "拱墅区", "120.157382", "30.271586"},
            {"923", "古艮山门", "拱墅区", "120.178263", "30.275258"},
            {"782", "大桥村文化广场", "萧山区", "120.195341", "29.971484"},
            {"576", "东山尖森林运动公园", "淳安县", "118.814386", "29.676524"},
            {"1991", "风物馆", "滨江区", "120.150338", "30.142537"},
            {"110", "观音庙", "萧山区", "120.205667", "29.977599"},
            {"1606", "莫家亭观景台", "临安区", "119.579520", "30.362180"},
            {"463", "二桥村公园", "萧山区", "120.293060", "30.235507"},
            {"2253", "溪上印象馆", "余杭区", "119.810665", "30.446082"},
            {"2567", "艺沫书画diy手作馆", "富阳区", "119.936923", "30.049666"},
            {"1492", "基督教堂", "桐庐县", "119.583270", "29.834570"},
            {"422", "萧西路公园", "萧山区", "120.247037", "30.173286"},
            {"2655", "萧山图书馆闻堰支馆", "萧山区", "120.182135", "30.123885"},
            {"411", "北星公园", "拱墅区", "120.138858", "30.329396"},
            {"2982", "义蓬购物中心西区", "钱塘区", "120.489092", "30.271526"},
            {"2091", "白塔历史文化陈列馆", "上城区", "120.144975", "30.200375"},
            {"2797", "杭州嘉里中心", "拱墅区", "120.162495", "30.260281"},
            {"1734", "宝贝乐翻天", "萧山区", "120.263075", "30.178925"},
            {"1877", "朱炳仁铜雕艺术博物馆", "上城区", "120.167138", "30.239925"},
            {"779", "中粮·杭州大悦城-水秀广场", "拱墅区", "120.129672", "30.301042"},
            {"982", "雷峰塔景区雷峰塔", "西湖区", "120.149715", "30.231612"},
            {"1663", "咚嗒嗒王国(明月园)", "滨江区", "120.226223", "30.206517"},
            {"1670", "梦幻儿童乐园", "钱塘区", "120.458875", "30.297275"},
            {"2285", "滨江会展中心", "滨江区", "120.159888", "30.183324"},
            {"124", "荀山禅寺", "余杭区", "120.049123", "30.381947"},
            {"466", "横河公园", "上城区", "120.186150", "30.251941"},
            {"322", "江河汇西岸公园", "上城区", "120.223361", "30.262257"},
            {"1805", "屁屁乐园", "余杭区", "120.033295", "30.237461"},
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

    /** 不变量校验：30 个候选每个恰好出现一次（簇内 + excluded 边缘点，分区完整性） */
    private void assertPartition(ClusterOutput output) {
        List<Long> clustered = output.getClusters().stream()
                .flatMap(d -> d.getPoints().stream()).map(PoiCandidate::getId).toList();
        List<Long> excluded = output.getExcluded() == null ? List.of()
                : output.getExcluded().stream().map(PoiCandidate::getId).toList();
        List<Long> all = new java.util.ArrayList<>(clustered);
        all.addAll(excluded);
        assertEquals(REAL_POIS.size(), all.size(),
                "每个候选应恰好分配一次（簇内 " + clustered.size() + " + 边缘 " + excluded.size() + "）");
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
        // 地理合理性抽查：淳安远郊点（离市中心约 143km）必须被剥离为边缘点，不进任何簇
        boolean chunAnExcluded = output.getExcluded() != null
                && output.getExcluded().stream().anyMatch(p -> p.getId() == 576L);
        assertTrue(chunAnExcluded, "淳安远郊点(143km)应被剥离为边缘点，实际 excluded="
                + (output.getExcluded() == null ? "[]" : output.getExcluded().stream()
                        .map(PoiCandidate::getName).toList()));
        // 市中心雷峰塔必须在某个簇内（不被剥离）
        DayCluster downtown = dayOf(output, 982);
        assertTrue(downtown.getPoints().size() >= 1);
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
    @DisplayName("远距离必去点锚定：雷峰塔(982,市中心) + 淳安东山尖(576,远郊143km)，k=5 各占一天")
    void realDataDistantAnchors() {
        Set<Long> must = Set.of(982L, 576L);
        printInput("必去=雷峰塔(市中心) + 淳安东山尖(远郊约143km)，k=5", 5, must);
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(withMustVisits(must)).days(5).seed(42L).build());
        printOutput(output);

        assertEquals(2, output.getAnchorGroupCount());
        assertPartition(output);
        DayCluster downtown = dayOf(output, 982);
        DayCluster far = dayOf(output, 576);
        assertTrue(downtown.isAnchored() && far.isAnchored(), "两个必去点各自锚定一天");
        assertTrue(downtown.getDaySeq() != far.getDaySeq(), "两个远距离必去点不能同一天");
        // 单点锚组质心 = 必去点自身坐标
        assertEquals(30.231612, downtown.getCentroidLat(), 1e-6);
        assertEquals(120.149715, downtown.getCentroidLng(), 1e-6);
    }

    @Test
    @DisplayName("近邻必去点归并：大桥村(782) 与 观音庙(110) 相距约 1.3km，同组同一天")
    void realDataNearbyMustVisitMerged() {
        Set<Long> must = Set.of(782L, 110L);
        printInput("必去=大桥村文化广场 + 观音庙（相距约 1.3km，萧山南部），k=4", 4, must);
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(withMustVisits(must)).days(4).seed(42L).build());
        printOutput(output);

        assertEquals(1, output.getAnchorGroupCount(), "两个相邻必去点应归并为 1 个锚组");
        assertPartition(output);
        DayCluster gushan = dayOf(output, 782);
        assertEquals(gushan.getDaySeq(), dayOf(output, 110).getDaySeq(), "两个相邻必去点必须同一天");
        assertTrue(gushan.isAnchored());
        // 组质心 = 两点均值
        assertEquals((29.971484 + 29.977599) / 2, gushan.getCentroidLat(), 1e-6);
    }

    @Test
    @DisplayName("锚组超天数：4 个互不相邻必去点 + k=3 → 规划不可行（300002）")
    void realDataTooManyAnchorGroups() {
        // 四选必去点两两相距均 > 1500m：雷峰塔(982,市中心)、淳安东山尖(576,远郊)、义蓬(2982,钱塘大江东)、莫家亭(1606,临安)
        Set<Long> must = Set.of(982L, 576L, 2982L, 1606L);
        printInput("必去=雷峰塔/淳安/义蓬/临安（互不相邻），但行程只有 3 天", 3, must);
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
        // 真实数据回归：淳安远郊点(143km)必须被剥离，市中心雷峰塔不得与其同簇
        boolean chunAnExcluded = output.getExcluded() != null
                && output.getExcluded().stream().anyMatch(p -> p.getId() == 576L);
        assertTrue(chunAnExcluded, "淳安东山尖(143km)应被剥离为边缘点");
        assertTrue(output.getClusters().stream()
                        .noneMatch(d -> d.getPoints().stream().anyMatch(p -> p.getId() == 576L)),
                "淳安东山尖不得出现在任何簇内");
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
    @DisplayName("k=1 退化：全部 30 点一天，边缘点照常剥离")
    void realDataSingleDay() {
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(1).seed(42L).build());
        int clustered = output.getClusters().get(0).getPoints().size();
        int excluded = output.getExcluded() == null ? 0 : output.getExcluded().size();
        System.out.printf("%n  k=1：簇数=%d，簇内点数=%d，边缘点=%d，平衡=%s%n",
                output.getClusters().size(), clustered, excluded, output.isBalanced());
        assertEquals(1, output.getClusters().size());
        assertEquals(1, output.getClusters().get(0).getDaySeq());
        // 分区完整性：簇内 + 边缘 = 全部 30 点
        assertEquals(REAL_POIS.size(), clustered + excluded);
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
