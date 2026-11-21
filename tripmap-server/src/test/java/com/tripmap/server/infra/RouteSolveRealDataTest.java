package com.tripmap.server.infra;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.cluster.SpatialClusterTool;
import com.tripmap.tool.cluster.config.ClusterProperties;
import com.tripmap.tool.cluster.dto.ClusterInput;
import com.tripmap.tool.cluster.dto.ClusterOutput;
import com.tripmap.tool.cluster.dto.DayCluster;
import com.tripmap.tool.poi.PoiCandidate;
import com.tripmap.tool.tsp.RouteSolveTool;
import com.tripmap.tool.tsp.dto.RouteSolveInput;
import com.tripmap.tool.tsp.dto.RouteSolveOutput;
import com.tripmap.tool.tsp.dto.RouteStop;
import com.tripmap.tool.util.Haversine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 日内路径求解工具真实数据集成测试（任务 2.6.3 链路验证，真实 DB + 真实坐标）。
 *
 * <p>复用 {@code SpatialClusterRealDataTest} 的 30 点杭州真实 POI 快照：先跑
 * CP-SAT 聚类（k=5）得到逐日簇，再对每个簇构建通勤矩阵并调 {@link RouteSolveTool}
 * 求解日内路径——open_hours / suggest_duration 由工具凭 ID 走真实 DB（PoiMapper），
 * 覆盖：24h 假设（NULL open_hours）、多时段（萧山图书馆午间闭馆）、周日闭馆、
 * 晚开（11:00）、早闭（16:30）、suggest_duration 缺省回退默认 90min。</p>
 *
 * <p><b>通勤矩阵口径</b>：真实矩阵由 2.4 通勤工具（高德）承担；本测试聚焦 TSP
 * 与真实 POI 数据链路，故用坐标 haversine 直线距离按 {@code 30km/h} 市内近似换算
 * 为秒（仅测试用，运行期严禁以直线距离估算通勤，AGENTS.md 原则 1）。</p>
 *
 * <p>按 {@code @Tag("integration")} 默认被 surefire 排除（父 POM test.excluded.groups），
 * 手动执行：{@code mvn -pl tripmap-server test "-Dtest=RouteSolveRealDataTest" "-Dtest.excluded.groups="}；
 * 本地 MySQL 未监听或快照 POI 未入库时按假设跳过而非失败。</p>
 */
@Tag("integration")
@SpringBootTest(properties = "tripmap.amap.api-key=test-placeholder-key")
class RouteSolveRealDataTest {

    private static final String DB_HOST = "127.0.0.1";
    private static final int DB_PORT = 3306;

    /** 通勤矩阵近似车速（km/h，仅测试用，见类注释） */
    private static final double PROXY_SPEED_KMH = 30.0;

    @Autowired
    private RouteSolveTool routeSolveTool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------- 真实数据快照（与 SpatialClusterRealDataTest 同源，2026-11-20 自 tripmap.poi 抽样 30 条） ----------

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
                    .build())
            .toList();

    @BeforeAll
    static void checkMySqlReachable() {
        assumeTrue(portOpen(DB_HOST, DB_PORT),
                "本地 MySQL 未监听 %s:%d，跳过真实数据集成测试".formatted(DB_HOST, DB_PORT));
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("真实 30 点：聚类 k=5 → 逐簇日内路径（周六，真实 open_hours/时长 + 坐标近似矩阵）")
    void realDataClusterThenRoutePerDay() {
        LocalDate saturday = LocalDate.of(2026, 11, 28);
        assumeSnapshotLoaded();

        // 1. CP-SAT 聚类（复用 2.5 真实数据链路）
        SpatialClusterTool clusterTool = new SpatialClusterTool(new ClusterProperties());
        ClusterOutput clusters = clusterTool.execute(
                ClusterInput.builder().candidates(REAL_POIS).days(5).build());

        System.out.printf("%n===== 真实数据链路：聚类 k=5（%d 簇）→ 逐簇日内路径（%s %s）=====%n",
                clusters.getClusters().size(), saturday, saturday.getDayOfWeek());

        int solved = 0;
        int infeasible = 0;
        for (DayCluster day : clusters.getClusters()) {
            List<PoiCandidate> points = day.getPoints();
            List<Long> ids = points.stream().map(PoiCandidate::getId).toList();
            long[][] matrix = buildMatrix(points);

            System.out.printf("%n  --- 第 %d 天簇（%d 点）：%s ---%n", day.getDaySeq(), points.size(),
                    points.stream().map(p -> p.getName() + "[" + p.getDistrict() + "]")
                            .collect(java.util.stream.Collectors.joining("、")));
            try {
                RouteSolveOutput out = routeSolveTool.execute(RouteSolveInput.builder()
                        .poiIds(ids)
                        .durationsSeconds(matrix)
                        .date(saturday)
                        .startTime(LocalTime.of(9, 0))
                        .lunchStart(LocalTime.of(11, 30))
                        .lunchEnd(LocalTime.of(14, 0))
                        .defaultVisitMinutes(90)
                        .build());
                assertPermutation(ids, out.getOrderedPoiIds());
                assertEquals(commuteOf(ids, out.getOrderedPoiIds(), matrix),
                        out.getTotalCommuteSeconds(), "总通勤必须等于路径各段之和");
                printDayRoute(ids, out);
                solved++;
            } catch (BizException e) {
                assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode(),
                        "周六无闭馆，仅可能因时段过紧无解，码必须为 300002");
                System.out.printf("    无可行解（300002）：%s%n", e.getMessage());
                infeasible++;
            }
        }

        System.out.printf("%n  汇总：%d 簇求解成功，%d 簇无可行解（共 %d）%n",
                solved, infeasible, clusters.getClusters().size());
        assertTrue(solved >= 1, "至少应有一个簇能解出路径");
    }

    @Test
    @DisplayName("周一闭馆真实检测：含溪上印象馆(2253)/萧山图书馆(2655)的簇 → 300002")
    void mondayClosedDayDetected() {
        LocalDate monday = LocalDate.of(2026, 11, 23);
        assumeSnapshotLoaded();

        SpatialClusterTool clusterTool = new SpatialClusterTool(new ClusterProperties());
        ClusterOutput clusters = clusterTool.execute(
                ClusterInput.builder().candidates(REAL_POIS).days(5).build());

        DayCluster d2253 = dayOf(clusters, 2253L);
        DayCluster d2655 = dayOf(clusters, 2655L);
        assertNotEquals(d2253.getDaySeq(), d2655.getDaySeq(),
                "溪上印象馆(余杭)与萧山图书馆(萧山)分属不同区域簇");

        System.out.printf("%n===== 周一闭馆检测（%s %s）=====%n", monday, monday.getDayOfWeek());
        assertInfeasibleOn(d2253, monday, 2253L);
        assertInfeasibleOn(d2655, monday, 2655L);
    }

    // ---------- 断言辅助 ----------

    private void assertInfeasibleOn(DayCluster day, LocalDate date, long closedPoiId) {
        List<Long> ids = day.getPoints().stream().map(PoiCandidate::getId).toList();
        long[][] matrix = buildMatrix(day.getPoints());
        BizException e = assertThrows(BizException.class, () -> routeSolveTool.execute(
                RouteSolveInput.builder()
                        .poiIds(ids)
                        .durationsSeconds(matrix)
                        .date(date)
                        .startTime(LocalTime.of(9, 0))
                        .lunchStart(LocalTime.of(11, 30))
                        .lunchEnd(LocalTime.of(14, 0))
                        .defaultVisitMinutes(90)
                        .build()));
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), e.getCode());
        assertTrue(e.getMessage().contains(String.valueOf(closedPoiId)),
                "无解原因应点名闭馆景点 " + closedPoiId + "，实际: " + e.getMessage());
        System.out.printf("  第 %d 天簇 → 300002（闭馆 %d）：%s%n", day.getDaySeq(), closedPoiId, e.getMessage());
    }

    private void assertPermutation(List<Long> clusterIds, List<Long> ordered) {
        assertEquals(clusterIds.size(), ordered.size(), "路径必须恰好覆盖簇内所有点");
        assertEquals(new HashSet<>(clusterIds), new HashSet<>(ordered), "路径点集必须与簇一致");
        assertEquals(clusterIds.size(), new HashSet<>(ordered).size(), "路径不得重复访问同一点");
    }

    private void assumeSnapshotLoaded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poi WHERE id IN (" + idsCsv() + ")", Integer.class);
        assumeTrue(count != null && count == RAW.length,
                "快照 30 条 POI 未全部入库（当前 " + count + "/" + RAW.length + "），跳过（先跑 tripmap-poi 三脚本）");
    }

    // ---------- 通勤矩阵 / 人读日志辅助 ----------

    /** 坐标 haversine 直线距离按 30km/h 近似 → 秒（仅测试用，见类注释） */
    private long[][] buildMatrix(List<PoiCandidate> points) {
        int n = points.size();
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    matrix[i][j] = 0;
                    continue;
                }
                double meters = Haversine.distanceMeters(
                        points.get(i).getLatitude(), points.get(i).getLongitude(),
                        points.get(j).getLatitude(), points.get(j).getLongitude());
                matrix[i][j] = Math.round(meters * 3600 / (PROXY_SPEED_KMH * 1000));
            }
        }
        return matrix;
    }

    private long commuteOf(List<Long> clusterIds, List<Long> ordered, long[][] matrix) {
        long sum = 0;
        for (int k = 1; k < ordered.size(); k++) {
            int i = clusterIds.indexOf(ordered.get(k - 1));
            int j = clusterIds.indexOf(ordered.get(k));
            sum += matrix[i][j];
        }
        return sum;
    }

    private void printDayRoute(List<Long> ids, RouteSolveOutput out) {
        System.out.printf("    顺序=%s 总通勤=%ds 总等待=%ds 总耗时=%dmin%n",
                out.getOrderedPoiIds(), out.getTotalCommuteSeconds(),
                out.getTotalWaitSeconds(), out.getTotalElapsedMinutes());
        System.out.printf("    %-4s %-8s %-9s %-9s %-8s %-7s %-7s%n",
                "序", "POI", "到达", "离开", "游玩min", "等待s", "通勤s");
        int seq = 1;
        for (RouteStop stop : out.getStops()) {
            System.out.printf("    %-4d %-8d %-9s %-9s %-8d %-7d %-7d%s%s%n",
                    seq++, stop.getPoiId(),
                    hhmm(stop.getArriveMinuteOfDay()), hhmm(stop.getDepartMinuteOfDay()),
                    stop.getVisitMinutes(), stop.getWaitSeconds(), stop.getCommuteFromPrevSeconds(),
                    stop.isDefaultDurationApplied() ? " [默认时长]" : "",
                    stop.isDefaultOpenHoursApplied() ? " [24h假设]" : "");
        }
    }

    private String hhmm(int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    private DayCluster dayOf(ClusterOutput output, long poiId) {
        return output.getClusters().stream()
                .filter(d -> d.getPoints().stream().anyMatch(p -> p.getId() == poiId))
                .findFirst().orElseThrow();
    }

    private String idsCsv() {
        return java.util.Arrays.stream(RAW).map(row -> row[0])
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
