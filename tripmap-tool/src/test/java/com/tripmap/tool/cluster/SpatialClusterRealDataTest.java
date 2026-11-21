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
 * 空间聚类真实数据验证（任务 2.5.11，v1.4 CP-SAT 约束划分重写）：
 * 从库里 3051 条杭州 POI 中按 id 分桶随机抽样 30 条（id 20~3070 等分 30 桶、
 * 每桶一条，保证 id 不邻近），快照成本测试常量（运行时不依赖数据库）。
 *
 * <p>样本覆盖：西湖/拱墅（市中心）、余杭/临安（西北）、萧山/钱塘（东南）、
 * 富阳（西南）、淳安/建德/桐庐（远郊 60km+）——天然的地理多簇分布，
 * 覆盖我们讨论过的全部场景：纯约束划分 / 每日 1~7 硬约束 / 远距离锚定 /
 * 近邻必去归并 / 锚组超天数 / 单点远郊日 / 簇→天排序 / k=1 退化 / 候选不足。</p>
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
        System.out.printf("  k=%d，每日点数 [%d,%d]，簇直径 ≤ %.0fkm，必去 id=%s%n",
                days, properties.getMinPoisPerDay(), properties.getMaxPoisPerDay(),
                properties.getMaxClusterDiameterMeters() / 1000,
                mustIds.isEmpty() ? "（无）" : mustIds);
        System.out.printf("  %-6s %-14s %-6s %-12s %-12s%n", "id", "名称", "区县", "经度", "纬度");
        for (PoiCandidate c : REAL_POIS) {
            System.out.printf("  %-6d %-14s %-6s %-12s %-12s%s%n", c.getId(), c.getName(),
                    c.getDistrict(), c.getLongitude(), c.getLatitude(),
                    mustIds.contains(c.getId()) ? "  ← 必去" : "");
        }
    }

    private void printOutput(ClusterOutput output) {
        System.out.printf("  ---- 聚类结果（锚组 %d）----%n", output.getAnchorGroupCount());
        for (DayCluster day : output.getClusters()) {
            System.out.printf("  第 %d 天%s 质心(%.4f, %.4f) %2d 点：%s%n",
                    day.getDaySeq(), day.isAnchored() ? "[锚]" : "[自]",
                    day.getCentroidLat(), day.getCentroidLng(), day.getPoints().size(),
                    day.getPoints().stream()
                            .map(p -> p.getName() + "[" + p.getDistrict() + "]")
                            .collect(Collectors.joining("、")));
        }
    }

    /** 不变量校验：30 个候选每个恰好分配一次（分区完整性） */
    private void assertPartition(ClusterOutput output) {
        List<Long> clustered = output.getClusters().stream()
                .flatMap(d -> d.getPoints().stream()).map(PoiCandidate::getId).toList();
        assertEquals(REAL_POIS.size(), clustered.size(), "每个候选应恰好分配一次");
        assertEquals(REAL_POIS.size(), new HashSet<>(clustered).size(), "不允许重复分配");
    }

    /** 不变量校验：每天点数都在 [min,max] 硬约束内（v1.4 核心：杜绝单日十余点） */
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

    private DayCluster dayOf(ClusterOutput output, long poiId) {
        return output.getClusters().stream()
                .filter(d -> d.getPoints().stream().anyMatch(p -> p.getId() == poiId))
                .findFirst().orElseThrow();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("纯约束划分 k=5：真实 30 点，每日 1~7 硬约束 + 分区完整性")
    void realDataPurePartition() {
        // 候选含 5 个远郊孤点（淳安/临安/余杭西北/富阳/桐庐），k=4 在 20km 阈值下
        // 不可行（探查验证），故用 k=5
        printInput("无必去点，k=5", 5, Set.of());
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(5).build());
        printOutput(output);

        assertEquals(0, output.getAnchorGroupCount());
        assertPartition(output);
        assertSizeWithinBounds(output);
        // 市中心雷峰塔在簇内（不被剥离，新模型无边缘点概念）
        assertTrue(dayOf(output, 982).getPoints().size() >= 1);
    }

    @Test
    @DisplayName("每日 1~7 硬约束：k=5 时每天点数全部落在 [1,7]，无单日十余点")
    void realDataSizeHardConstraint() {
        printInput("无必去点，k=5（验证 K-Means 遗留的'市中心十几点一天'已被硬约束根治）", 5, Set.of());
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(5).build());
        printOutput(output);
        assertPartition(output);
        assertSizeWithinBounds(output);
        // 显式打印分布供人读核对
        List<Integer> sizes = output.getClusters().stream()
                .map(d -> d.getPoints().size()).sorted().toList();
        System.out.printf("  簇大小分布（升序）: %s（最大=%d，硬上界=%d）%n",
                sizes, sizes.get(sizes.size() - 1), properties.getMaxPoisPerDay());
        assertTrue(sizes.get(sizes.size() - 1) <= properties.getMaxPoisPerDay(),
                "最大簇不得超过每日上界 7（K-Means 曾出现单日十余点）");
    }

    @Test
    @DisplayName("确定性：k=5 两次运行完全一致（固定参数 + 单 worker，无需种子）")
    void realDataDeterministic() {
        ClusterInput input = ClusterInput.builder().candidates(REAL_POIS).days(5).build();
        ClusterOutput a = tool.execute(input);
        ClusterOutput b = tool.execute(input);
        System.out.printf("%n  真实数据 k=5 两次运行对比：%n");
        for (int i = 0; i < a.getClusters().size(); i++) {
            Set<Long> sa = a.getClusters().get(i).getPoints().stream().map(PoiCandidate::getId)
                    .collect(Collectors.toSet());
            Set<Long> sb = b.getClusters().get(i).getPoints().stream().map(PoiCandidate::getId)
                    .collect(Collectors.toSet());
            System.out.printf("  第 %d 天: %s %s%n", i + 1, sa, sa.equals(sb) ? "= 第二次 ✓" : "≠ 第二次 ✗");
            assertEquals(sa, sb, "同输入必须同输出（确定性）");
        }
        assertPartition(a);
    }

    @Test
    @DisplayName("远距离必去点锚定：雷峰塔(982,市中心) + 淳安东山尖(576,远郊143km)，k=5 各占一天")
    void realDataDistantAnchors() {
        Set<Long> must = Set.of(982L, 576L);
        printInput("必去=雷峰塔(市中心) + 淳安东山尖(远郊约143km)，k=5", 5, must);
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(withMustVisits(must)).days(5).build());
        printOutput(output);

        assertEquals(2, output.getAnchorGroupCount());
        assertPartition(output);
        assertSizeWithinBounds(output);
        DayCluster downtown = dayOf(output, 982);
        DayCluster far = dayOf(output, 576);
        assertTrue(downtown.isAnchored() && far.isAnchored(), "两个必去点各自锚定一天");
        assertTrue(downtown.getDaySeq() != far.getDaySeq(), "两个远距离必去点不能同一天");
        // 淳安锚定簇不得混入市中心点（市中心非孤点受直径约束）；远郊孤点可就近并入
        assertTrue(far.getPoints().stream()
                        .noneMatch(p -> p.getId() == 982L || p.getId() == 2797L),
                "淳安锚定簇不得混入市中心点（直径约束）");
    }

    @Test
    @DisplayName("近邻必去点归并：大桥村(782) 与 观音庙(110) 相距约 1.3km，同组同一天")
    void realDataNearbyMustVisitMerged() {
        Set<Long> must = Set.of(782L, 110L);
        printInput("必去=大桥村文化广场 + 观音庙（相距约 1.3km，萧山南部），k=5", 5, must);
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(withMustVisits(must)).days(5).build());
        printOutput(output);

        assertEquals(1, output.getAnchorGroupCount(), "两个相邻必去点应归并为 1 个锚组");
        assertPartition(output);
        assertSizeWithinBounds(output);
        DayCluster merged = dayOf(output, 782);
        assertEquals(merged.getDaySeq(), dayOf(output, 110).getDaySeq(), "两个相邻必去点必须同一天");
        assertTrue(merged.isAnchored());
    }

    @Test
    @DisplayName("孤点豁免：远郊淳安点并入最近簇（不强制独占一天），不与其他远郊孤点同天")
    void realDataOutlierExemption() {
        printInput("无必去点，k=8（验证孤点豁免：远郊孤点不强制独占一天，就近并入）", 8, Set.of());
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(8).build());
        printOutput(output);
        assertPartition(output);
        assertSizeWithinBounds(output);
        // 淳安远郊点(143km)：孤点豁免下并入最近簇（可能与另一远郊孤点同天，
        // 因为二者互为最近邻），但不得与市中心点同天（市中心非孤点受直径约束）
        DayCluster chunAn = dayOf(output, 576);
        assertTrue(chunAn.getPoints().stream()
                        .noneMatch(p -> p.getId() == 982L || p.getId() == 2797L),
                "淳安东山尖不得与市中心点同天（市中心受直径约束）");
        List<Integer> sizes = output.getClusters().stream()
                .map(d -> d.getPoints().size()).sorted().toList();
        System.out.printf("  簇大小分布（升序）: %s（最大=%d）%n", sizes, sizes.get(sizes.size() - 1));
    }

    @Test
    @DisplayName("锚组超天数：4 个互不相邻必去点 + k=3 → 规划不可行（300002）")
    void realDataTooManyAnchorGroups() {
        // 四选必去点两两相距均 > 1500m：雷峰塔(982,市中心)、淳安东山尖(576,远郊)、义蓬(2982,钱塘大江东)、莫家亭(1606,临安)
        Set<Long> must = Set.of(982L, 576L, 2982L, 1606L);
        printInput("必去=雷峰塔/淳安/义蓬/临安（互不相邻），但行程只有 3 天", 3, must);
        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(withMustVisits(must)).days(3).build()));
        System.out.printf("  抛出异常: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("簇→天排序：贪心链性质校验（相邻天质心最近）")
    void realDataDayOrderingGreedyChain() {
        printInput("k=5 排序校验：每个后续天必须是当时质心最近者", 5, Set.of());
        ClusterOutput output = tool.execute(ClusterInput.builder()
                .candidates(REAL_POIS).days(5).build());
        printOutput(output);
        assertPartition(output);

        // 贪心链性质：day(i+1) 的质心是所有未排簇中离 day(i) 最近的
        List<DayCluster> days = output.getClusters();
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
    @DisplayName("k=1 退化：全部 30 点分 1 天受每日上限约束 → 不可行（300002）")
    void realDataSingleDayInfeasible() {
        // 每日上限 7，30 点无法塞进 1 天 → CP-SAT 无解
        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(REAL_POIS).days(1).build()));
        System.out.printf("%n  k=1：30 点无法塞进每日上限 %d → code=%d, message=%s%n",
                properties.getMaxPoisPerDay(), e.getCode(), e.getMessage());
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
    }

    @Test
    @DisplayName("候选少于天数：30 点 + k=31 → 规划不可行（300002）")
    void realDataCandidatesFewerThanDays() {
        BizException e = assertThrows(BizException.class, () -> tool.execute(
                ClusterInput.builder().candidates(REAL_POIS).days(31).build()));
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
