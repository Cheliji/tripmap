package com.tripmap.tool.tsp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日内路径精确枚举器单测（任务 2.6.1 / 2.6.2 / 2.6.3 / 2.6.5，设计说明书 §6.3）。
 *
 * <p>纯本地算法（矩阵为入参、无 DB/外部 API），直接测 {@link RouteEnumerator}：
 * 目标最优（2.6.1）、时间窗剪枝与方向相关（2.6.2）、午餐禁游（2026-11 对齐新增）、
 * 缺失边剪枝、确定性、6 点西湖环线手工推算最优（2.6.3）、7 点性能（2.6.5）。</p>
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入与输出，console 可直接人工 review。</p>
 */
class RouteEnumeratorTest {

    /** 24h 开放（秒）：[0, 86400] */
    private static final int[][] OPEN_24H = {{0, 24 * 3600}};

    // ---------- 2.6.1 全排列精确枚举 + 数学最优 ----------

    @Test
    @DisplayName("三点一线：最优开放路径为中间点居中，总通勤最小")
    void enumerationFindsOptimalOpenPath() {
        System.out.println("【场景】三点一线 A-B-C，d(A,B)=600s d(B,C)=5400s d(A,C)=6000s，开放 24h、无午餐");
        long[][] matrix = {
                {0, 600, 6000},
                {600, 0, 5400},
                {6000, 5400, 0}
        };
        int[] visit = {3600, 3600, 3600};
        int[][] open = {OPEN_24H[0], OPEN_24H[0], OPEN_24H[0]};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(11, 30) /* 禁用午餐 */);

        assertNotNull(r);
        System.out.printf("输出顺序=%s 总通勤=%ds 总等待=%ds%n",
                Arrays.toString(r.order), r.commuteSeconds, r.waitSeconds);
        // 最优开放路径 0-1-2（或反向 2-1-0）通勤 = 600+5400 = 6000s；DFS 先到 [0,1,2]
        assertArrayEquals(new int[]{0, 1, 2}, r.order);
        assertEquals(6000L, r.commuteSeconds);
        assertEquals(0L, r.waitSeconds);
    }

    // ---------- 2.6.2 时间窗剪枝（含"方向相关"说明） ----------

    @Test
    @DisplayName("时间窗：早闭景点必须排前，反向因闭馆被剪枝")
    void timeWindowPrunesByCloseAndDirectionMatters() {
        System.out.println("【场景】POI0 开放 10:00-18:00、POI1 开放 09:00-10:00（10 点闭），各游 60min、通勤 600s、09:00 出发");
        long[][] matrix = {{0, 600}, {600, 0}};
        int[] visit = {3600, 3600};
        // POI0: 10:00-18:00；POI1: 09:00-10:00
        int[][] open = {{sec(10, 0), sec(18, 0)}, {sec(9, 0), sec(10, 0)}};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(11, 30));

        assertNotNull(r);
        System.out.printf("输出顺序=%s（0 先则 10 点闭馆剪枝；1 先可行）%n", Arrays.toString(r.order));
        // 反向 [0,1]：0 等到 10:00→11:00 离开，到 1 已 11:10 超闭馆 → 剪枝；唯 [1,0] 可行
        assertArrayEquals(new int[]{1, 0}, r.order);
        assertEquals(600L, r.commuteSeconds);
        assertEquals(0L, r.waitSeconds);
        // 逐站时刻：1 站 09:00-10:00；0 站 10:10-11:10
        assertEquals(sec(9, 0), r.arriveSeconds[0]);
        assertEquals(sec(10, 0), r.departSeconds[0]);
        assertEquals(sec(10, 10), r.arriveSeconds[1]);
        assertEquals(sec(11, 10), r.departSeconds[1]);
    }

    @Test
    @DisplayName("时间窗：未到开放时间允许等待并计入耗时")
    void waitsUntilOpen() {
        System.out.println("【场景】单点 10:00 开门、游 60min、09:00 出发 → 等待 1h 到 10:00");
        long[][] matrix = {{0}};
        int[] visit = {3600};
        int[][] open = {{sec(10, 0), sec(18, 0)}};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(11, 30));

        assertNotNull(r);
        System.out.printf("到达=%ds 离开=%ds 等待=%ds%n",
                r.arriveSeconds[0], r.departSeconds[0], r.waitSecondsPerNode[0]);
        assertEquals(sec(10, 0), r.arriveSeconds[0]);
        assertEquals(sec(11, 0), r.departSeconds[0]);
        assertEquals(3600L, r.waitSecondsPerNode[0]);
    }

    // ---------- 午餐禁游（2026-11 对齐新增） ----------

    @Test
    @DisplayName("午餐禁游：游玩区间相交时整体顺延到午餐结束")
    void lunchWindowPushesVisit() {
        System.out.println("【场景】24h 开放、游 90min、10:30 出发、午餐 11:30-14:00 → 顺延到 14:00 开始");
        long[][] matrix = {{0}};
        int[] visit = {5400}; // 90min
        int[][] open = {OPEN_24H[0]};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(10, 30), sec(11, 30), sec(14, 0));

        assertNotNull(r);
        System.out.printf("到达=%ds(14:00) 离开=%ds(15:30) 等待=%ds%n",
                r.arriveSeconds[0], r.departSeconds[0], r.waitSecondsPerNode[0]);
        assertEquals(sec(14, 0), r.arriveSeconds[0]);
        assertEquals(sec(15, 30), r.departSeconds[0]);
        assertEquals(sec(14, 0) - sec(10, 30), r.waitSecondsPerNode[0]);
    }

    @Test
    @DisplayName("午餐禁游：顺延后超闭馆 → 剪枝 → 无解")
    void lunchWindowPushViolatesClose() {
        System.out.println("【场景】单点 09:00-12:30 开放、游 90min、11:00 出发、午餐 11:30-14:00 → 顺延到 14:00 超闭馆");
        long[][] matrix = {{0}};
        int[] visit = {5400};
        int[][] open = {{sec(9, 0), sec(12, 30)}};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(11, 0), sec(11, 30), sec(14, 0));

        assertNull(r);
        System.out.println("输出=null（全部排列不可行，符合预期）");
    }

    @Test
    @DisplayName("午餐禁游：lunchStart==lunchEnd 表示禁用")
    void lunchDisabledWhenStartEqualsEnd() {
        System.out.println("【场景】24h、游 90min、10:30 出发、午餐禁用 → 10:30 直接开始，12:00 结束");
        long[][] matrix = {{0}};
        int[] visit = {5400};
        int[][] open = {OPEN_24H[0]};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(10, 30), sec(12, 0), sec(12, 0));

        assertNotNull(r);
        System.out.printf("到达=%ds 离开=%ds 等待=%ds%n",
                r.arriveSeconds[0], r.departSeconds[0], r.waitSecondsPerNode[0]);
        assertEquals(sec(10, 30), r.arriveSeconds[0]);
        assertEquals(sec(12, 0), r.departSeconds[0]);
        assertEquals(0L, r.waitSecondsPerNode[0]);
    }

    // ---------- 缺失通勤边剪枝 ----------

    @Test
    @DisplayName("缺失通勤边（-1）：含该边的顺序剪枝，走可行方向")
    void missingEdgePrunes() {
        System.out.println("【场景】两点，d(0,1)=-1 缺失、d(1,0)=600s 可用 → 唯 [1,0] 可行");
        long[][] matrix = {{0, -1}, {600, 0}};
        int[] visit = {3600, 3600};
        int[][] open = {OPEN_24H[0], OPEN_24H[0]};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(11, 30));

        assertNotNull(r);
        System.out.printf("输出顺序=%s 总通勤=%ds%n", Arrays.toString(r.order), r.commuteSeconds);
        assertArrayEquals(new int[]{1, 0}, r.order);
        assertEquals(600L, r.commuteSeconds);
    }

    // ---------- 2.6.3 西湖环线手工推算最优 ----------

    @Test
    @DisplayName("2.6.3 六点西湖环线抽象：最优开放路径 = 环线去掉一条边（5×600s）")
    void westLakeCircleHandVerified() {
        System.out.println("【场景】6 点环线（断桥→白堤→孤山→苏堤→雷峰塔→湖滨→断桥），相邻 600s、非相邻 3000s；"
                + "开放 24h、无午餐");
        int n = 6;
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    matrix[i][j] = 0;
                } else {
                    boolean adjacent = (i + 1) % n == j || (j + 1) % n == i;
                    matrix[i][j] = adjacent ? 600 : 3000;
                }
            }
        }
        int[] visit = {3600, 3600, 3600, 3600, 3600, 3600};
        int[][] open = {OPEN_24H[0], OPEN_24H[0], OPEN_24H[0], OPEN_24H[0], OPEN_24H[0], OPEN_24H[0]};

        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(11, 30));

        assertNotNull(r);
        System.out.printf("输出顺序=%s 总通勤=%ds%n", Arrays.toString(r.order), r.commuteSeconds);
        // 环线最小开放路径：去掉一条环边，5×600=3000s；DFS 先到环序 [0,1,2,3,4,5]
        assertEquals(3000L, r.commuteSeconds);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5}, r.order);
    }

    // ---------- 确定性 ----------

    @Test
    @DisplayName("确定性：同输入同输出")
    void deterministicSameInputSameOutput() {
        System.out.println("【场景】4 点混合开放时段，同一输入求解两次，输出一致");
        long[][] matrix = {
                {0, 900, 1800, 1200},
                {900, 0, 600, 1500},
                {1800, 600, 0, 900},
                {1200, 1500, 900, 0}
        };
        int[] visit = {3600, 5400, 2700, 3600};
        int[][] open = {
                OPEN_24H[0],
                {sec(8, 30), sec(17, 0)},
                {sec(9, 0), sec(16, 30)},
                OPEN_24H[0]
        };

        RouteEnumerator.SolveResult a = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(14, 0));
        RouteEnumerator.SolveResult b = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(14, 0));

        assertNotNull(a);
        assertNotNull(b);
        assertArrayEquals(a.order, b.order);
        assertEquals(a.commuteSeconds, b.commuteSeconds);
        assertEquals(a.waitSeconds, b.waitSeconds);
        System.out.printf("两次顺序=%s 总通勤=%ds 总等待=%ds（一致）%n",
                Arrays.toString(a.order), a.commuteSeconds, a.waitSeconds);
    }

    // ---------- 2.6.5 性能 ----------

    @Test
    @DisplayName("2.6.5 性能：7 点全排列（5040 条）毫秒级求解")
    void sevenPointsFast() {
        System.out.println("【场景】7 点随机对称矩阵、24h 开放、无午餐（剪枝最弱的最坏情形）");
        int n = 7;
        Random random = new Random(42);
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long v = 300 + random.nextInt(2700);
                matrix[i][j] = v;
                matrix[j][i] = v;
            }
        }
        int[] visit = new int[n];
        int[][] open = new int[n][];
        Arrays.fill(visit, 3600);
        for (int i = 0; i < n; i++) {
            open[i] = OPEN_24H[0];
        }

        long start = System.nanoTime();
        RouteEnumerator.SolveResult r = RouteEnumerator.solve(
                matrix, visit, open, sec(9, 0), sec(11, 30), sec(11, 30));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(r);
        System.out.printf("7 点求解耗时=%dms 顺序=%s 总通勤=%ds%n",
                elapsedMs, Arrays.toString(r.order), r.commuteSeconds);
        assertTrue(elapsedMs < 1000, "7 点全排列应毫秒级完成，实际 " + elapsedMs + "ms");
    }

    // ---------- 辅助 ----------

    private static int sec(int hour, int minute) {
        return hour * 3600 + minute * 60;
    }
}
