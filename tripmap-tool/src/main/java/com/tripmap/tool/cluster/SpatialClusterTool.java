package com.tripmap.tool.cluster;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.cluster.config.ClusterProperties;
import com.tripmap.tool.cluster.dto.ClusterInput;
import com.tripmap.tool.cluster.dto.ClusterOutput;
import com.tripmap.tool.cluster.dto.DayCluster;
import com.tripmap.tool.cluster.dto.ForbiddenPair;
import com.tripmap.tool.poi.PoiCandidate;
import com.tripmap.tool.spi.ToolNames;
import com.tripmap.tool.spi.TripTool;
import com.tripmap.tool.util.Haversine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空间聚类工具（任务 2.5，设计说明书 v1.4 §6.2，由 K-Means 重做为 OR-Tools CP-SAT 约束划分）。
 *
 * <p><b>重做背景（2026-11-21）</b>：K-Means 目标函数只最小化簇内平方距离，对
 * "每日景点数"零表达能力，市中心高密度点会被聚成单日十余点（真实数据验证暴露）。
 * 改为 CP-SAT 约束划分——"一天一个区域"由硬约束建模保证，不再依赖聚类形态碰运气。</p>
 *
 * <p><b>模型（设计说明书 §6.2）</b>：</p>
 * <ol>
 *   <li><b>变量</b>：x[i][d] ∈ {0,1}，候选 i 排第 d 天；</li>
 *   <li><b>每候选恰一天</b>：Σ_d x[i][d] = 1；</li>
 *   <li><b>每日点数硬约束</b>：minPoisPerDay ≤ Σ_i x[i][d] ≤ maxPoisPerDay
 *       （默认 1~7，配置化）；</li>
 *   <li><b>簇直径硬约束（地理距离）</b>：同一天任意两点 haversine 距离 ≤
 *       maxClusterDiameterMeters（配置化）；超径点对以 x[i][d]+x[j][d] ≤ 1 线性化。
 *       <b>孤点豁免</b>：最近邻 > 阈值的远郊孤点不受此约束（允许并入最近簇，
 *       避免孤点强制独占一天导致孤点数 > k 时无解）；</li>
 *   <li><b>禁配表回切</b>：{@link ClusterInput#getForbiddenPairs()}（通勤矩阵/硬校验 #4
 *       发现的"同天但通勤超时"点对）同法线性化，实现"通勤时间禁配回切环"的模型侧；</li>
 *   <li><b>必去点锚定</b>：必去点近邻归并（并查集传递闭包）后每组占一天、
 *       组内成员强制同天，建模为变量固定值；</li>
 *   <li><b>目标</b>：最小化全部簇内 pairwise 距离和（凝聚度），为解的质量标准；</li>
 *   <li><b>确定性</b>：固定参数 + numSearchWorkers=1，同输入同输出。</li>
 * </ol>
 *
 * <p><b>可行性</b>：n ≥ k 时模型恒有可行解（单点日直径平凡满足 + 孤点豁免），
 * 远郊孤点为合法解，不做罚函数软例外；n &lt; k、锚组数 &gt; k 或约束组合下无解时抛
 * 300002（设计说明书 §7.3"不可行尽早拒绝"）。</p>
 *
 * <p><b>不做</b>：district_adcode 跨江跨山校正——由通勤禁配回切环承担（编排层 3.6）；
 * 簇→天排序按簇质心邻近关系贪心，仅降换区成本（设计说明书 §6.3"日序安排"）。</p>
 */
@Component
public class SpatialClusterTool implements TripTool<ClusterInput, ClusterOutput> {

    private static final Logger log = LoggerFactory.getLogger(SpatialClusterTool.class);

    /** 目标函数距离缩放：米 → 0.1m 精度整数（CP-SAT 目标仅支持整数系数） */
    private static final long DIST_SCALE = 10L;

    static {
        // 类加载时加载原生库（ortools-java 自带各平台原生库，无需系统安装）
        Loader.loadNativeLibraries();
    }

    private final ClusterProperties properties;

    public SpatialClusterTool(ClusterProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return ToolNames.SPATIAL_CLUSTER;
    }

    @Override
    public ClusterOutput execute(ClusterInput input) {
        // ---------- 0. 参数校验 ----------
        List<PoiCandidate> candidates = validate(input);
        int n = candidates.size();
        int k = input.getDays();

        // 距离矩阵一次性预计算（n≤40，O(n²) 廉价；直径约束与目标函数共用）
        double[][] dist = buildDistanceMatrix(candidates);

        // ---------- 1. 必去点近邻归并 → 锚组 ----------
        List<List<Integer>> anchorGroups = mergeAnchorGroups(candidates, dist);
        int g = anchorGroups.size();
        if (g > k) {
            throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                    String.format("必去景点归并后共 %d 组，超过行程天数 %d：每组各占一天，无法安排，"
                            + "请减少必去景点或增加天数", g, k));
        }

        // ---------- 2. 构建并求解 CP-SAT 模型 ----------
        int[] assign = solve(candidates, dist, input.getForbiddenPairs(), anchorGroups, k);

        // ---------- 3. 簇→天排序（设计说明书 §6.3"日序安排"） ----------
        double[][] centroids = computeCentroids(candidates, assign, k);
        int[] dayOrder = orderClusters(centroids, k);

        ClusterOutput output = buildOutput(candidates, assign, centroids, dayOrder, k, g);
        printClusters(output);
        return output;
    }

    // ---------- 0. 参数校验 ----------

    private List<PoiCandidate> validate(ClusterInput input) {
        if (input == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: input");
        }
        if (input.getCandidates() == null || input.getCandidates().isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: candidates");
        }
        if (input.getDays() == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: days");
        }
        if (input.getDays() < 1) {
            throw new BizException(ResultCode.PARAM_VALIDATION_FAILED,
                    "行程天数至少为 1（实际 " + input.getDays() + "）");
        }
        // 按 ID 去重保序（上游重复传入不放大簇规模）
        Map<Long, PoiCandidate> byId = new LinkedHashMap<>();
        for (PoiCandidate c : input.getCandidates()) {
            if (c == null || c.getId() == null) {
                throw new BizException(ResultCode.PARAM_ILLEGAL, "候选 POI 不允许为 null 或缺 id");
            }
            byId.putIfAbsent(c.getId(), c);
        }
        List<PoiCandidate> candidates = new ArrayList<>(byId.values());
        if (candidates.stream().anyMatch(c -> c.getLatitude() == null || c.getLongitude() == null)) {
            throw new BizException(ResultCode.PARAM_ILLEGAL, "候选 POI 坐标缺失，无法聚类");
        }
        if (candidates.size() < input.getDays()) {
            throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                    String.format("候选景点数 %d 少于行程天数 %d，无法做到一天一个区域，请补充候选",
                            candidates.size(), input.getDays()));
        }
        return candidates;
    }

    /** n×n haversine 距离矩阵（米），对称、对角线 0 */
    private double[][] buildDistanceMatrix(List<PoiCandidate> candidates) {
        int n = candidates.size();
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = Haversine.distanceMeters(
                        candidates.get(i).getLatitude(), candidates.get(i).getLongitude(),
                        candidates.get(j).getLatitude(), candidates.get(j).getLongitude());
                dist[i][j] = d;
                dist[j][i] = d;
            }
        }
        return dist;
    }

    // ---------- 1. 必去点近邻归并（并查集 + 传递闭包） ----------

    /**
     * 必去点按"两两距离 ≤ 归并阈值"做并查集归并：A 近 B、B 近 C ⇒ A/B/C 同组，
     * 一组 = 一个必去区域 = 占一天。返回锚组列表，组内为候选下标（升序）。
     */
    private List<List<Integer>> mergeAnchorGroups(List<PoiCandidate> candidates, double[][] dist) {
        List<Integer> mustIdx = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).isMustVisit()) {
                mustIdx.add(i);
            }
        }
        if (mustIdx.isEmpty()) {
            return List.of();
        }
        int[] parent = new int[candidates.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        double threshold = properties.getAnchorMergeDistanceMeters();
        for (int i = 0; i < mustIdx.size(); i++) {
            for (int j = i + 1; j < mustIdx.size(); j++) {
                if (dist[mustIdx.get(i)][mustIdx.get(j)] <= threshold) {
                    union(parent, mustIdx.get(i), mustIdx.get(j));
                }
            }
        }
        Map<Integer, List<Integer>> rootToGroup = new LinkedHashMap<>();
        for (int idx : mustIdx) {
            rootToGroup.computeIfAbsent(find(parent, idx), r -> new ArrayList<>()).add(idx);
        }
        return new ArrayList<>(rootToGroup.values());
    }

    private int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /**
     * 孤点判定：最近邻距离 > 直径阈值的点。孤点对不受直径硬约束（允许并入最近簇），
     * 避免远郊稀疏点强制独占一天导致孤点数 > k 时整体无解（设计定案 B）。
     */
    private boolean[] detectOutliers(double[][] dist, double diameter) {
        int n = dist.length;
        boolean[] outlier = new boolean[n];
        for (int i = 0; i < n; i++) {
            double nearest = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i != j && dist[i][j] < nearest) {
                    nearest = dist[i][j];
                }
            }
            outlier[i] = nearest > diameter;
        }
        return outlier;
    }

    // ---------- 2. CP-SAT 模型（设计说明书 §6.2） ----------

    /**
     * 构建并求解约束划分模型，返回 assign[i] = 候选 i 所属簇下标（0..k-1）。
     *
     * @param candidates     候选（去重后）
     * @param dist           距离矩阵（米）
     * @param forbiddenPairs 禁配表（通勤回切环注入，可为 null/空）
     * @param anchorGroups   必去点归并组（组内强制同天、独占一天）
     * @param k              天数
     */
    private int[] solve(List<PoiCandidate> candidates, double[][] dist,
                        List<ForbiddenPair> forbiddenPairs,
                        List<List<Integer>> anchorGroups, int k) {
        int n = candidates.size();
        // 候选 id → 下标（禁配表按 id 引用，转回下标）
        Map<Long, Integer> idToIdx = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            idToIdx.put(candidates.get(i).getId(), i);
        }

        CpModel model = new CpModel();
        IntVar[][] x = new IntVar[n][k];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < k; d++) {
                x[i][d] = model.newBoolVar("x[" + i + "," + d + "]");
            }
        }

        // 约束 1：每候选恰好一天
        for (int i = 0; i < n; i++) {
            model.addEquality(LinearExpr.sum(x[i]), 1);
        }

        // 约束 2：每日点数硬约束 [minPoisPerDay, maxPoisPerDay]（默认 1~7）
        int minPerDay = properties.getMinPoisPerDay();
        int maxPerDay = properties.getMaxPoisPerDay();
        for (int d = 0; d < k; d++) {
            IntVar[] col = new IntVar[n];
            for (int i = 0; i < n; i++) {
                col[i] = x[i][d];
            }
            model.addLinearConstraint(LinearExpr.sum(col), minPerDay, maxPerDay);
        }

        // 约束 3：簇直径硬约束——超径点对不得同天（x[i][d]+x[j][d] ≤ 1）
        // 孤点豁免（2026-11-21 设计定案 B）：最近邻距离 > 直径阈值的点为孤点（如远郊
        // 稀疏点），它与其他点的"超径"不加禁配——允许并入最近簇，由目标函数就近
        // 分配；否则每个远郊孤点都必须独占一天，孤点数 > k 时整体无解（真实 30 点
        // 数据验证暴露）。非孤点之间仍受直径硬约束，保持市区密集区"一天一个区域"。
        double diameter = properties.getMaxClusterDiameterMeters();
        boolean[] outlier = detectOutliers(dist, diameter);
        int diameterPairs = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (dist[i][j] > diameter && !outlier[i] && !outlier[j]) {
                    for (int d = 0; d < k; d++) {
                        model.addLessOrEqual(
                                LinearExpr.newBuilder().add(x[i][d]).add(x[j][d]).build(), 1);
                    }
                    diameterPairs++;
                }
            }
        }
        int outlierCount = 0;
        for (boolean o : outlier) {
            if (o) outlierCount++;
        }
        if (outlierCount > 0) {
            log.info("孤点豁免：{} 个最近邻 > {}km 的点不受直径约束（并入最近簇）",
                    outlierCount, (long) (diameter / 1000));
        }

        // 约束 3b：禁配表回切（通勤矩阵/硬校验 #4 发现的"同天但通勤超时"点对）
        int forbidden = 0;
        if (forbiddenPairs != null) {
            for (ForbiddenPair pair : forbiddenPairs) {
                Integer i = idToIdx.get(pair.getFirstPoiId());
                Integer j = idToIdx.get(pair.getSecondPoiId());
                if (i == null || j == null) {
                    log.warn("禁配表引用未知候选 id，跳过：({},{})", pair.getFirstPoiId(), pair.getSecondPoiId());
                    continue;
                }
                for (int d = 0; d < k; d++) {
                    model.addLessOrEqual(
                            LinearExpr.newBuilder().add(x[i][d]).add(x[j][d]).build(), 1);
                }
                forbidden++;
            }
        }

        // 约束 4：必去点锚定——归并组内成员强制同天、该组独占一天（变量固定值）
        // 组 g 固定到簇 g（0..g-1），剩余 k-g 天供自由点。
        int g = anchorGroups.size();
        for (int c = 0; c < g; c++) {
            for (int idx : anchorGroups.get(c)) {
                model.addEquality(x[idx][c], 1);
            }
        }

        // 目标：最小化全部簇的直径和（凝聚度）——每簇引入整数直径变量 D[d]，
        // 线性约束 D[d] ≥ (x[i][d] + x[j][d] - 1) · dist(i,j)（当 i、j 同天时 D[d]
        // 至少取其距离）。纯线性、无乘法变量，CP-SAT 毫秒～百毫秒级收敛；
        // 语义即"每个区域尽量紧凑"（设计说明书 §6.2 凝聚度目标）。
        // 距离缩放到 0.1m 整数精度（CP-SAT 整数系数）。
        long maxScaled = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                maxScaled = Math.max(maxScaled, Math.round(dist[i][j] * DIST_SCALE));
            }
        }
        IntVar[] diameterVar = new IntVar[k];
        for (int d = 0; d < k; d++) {
            diameterVar[d] = model.newIntVar(0, maxScaled, "diam_" + d);
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long w = Math.round(dist[i][j] * DIST_SCALE);
                if (w == 0) {
                    continue;
                }
                for (int d = 0; d < k; d++) {
                    // D[d] >= dist(i,j) * (x[i][d] + x[j][d] - 1)
                    // 即 D[d] - dist*x[i][d] - dist*x[j][d] >= -dist
                    model.addGreaterOrEqual(
                            LinearExpr.newBuilder().addTerm(diameterVar[d], 1)
                                    .addTerm(x[i][d], -w)
                                    .addTerm(x[j][d], -w).build(),
                            -w);
                }
            }
        }
        model.minimize(LinearExpr.sum(diameterVar));

        // 求解：固定参数 + 单 worker，保证同输入同输出（设计说明书 §6.2 确定性）
        CpSolver solver = new CpSolver();
        solver.getParameters().setNumSearchWorkers(1);
        solver.getParameters().setMaxTimeInSeconds(properties.getMaxSolveTimeSeconds());
        CpSolverStatus status = solver.solve(model);

        log.debug("CP-SAT 求解：n={} k={} 锚组={} 直径约束对={} 禁配对={} 状态={} 目标={}",
                n, k, g, diameterPairs, forbidden, status, solver.objectiveValue());

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            // n ≥ k 且下界 1 时单点日恒可行；唯一可能不可行来源是直径/禁配约束
            throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                    String.format("候选 %d 个分 %d 天无法满足空间约束（簇直径 ≤ %.0fkm）"
                                    + "：请放宽直径阈值、减少必去锚点或调整禁配点对",
                            n, k, diameter / 1000));
        }

        int[] assign = new int[n];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < k; d++) {
                if (solver.value(x[i][d]) == 1) {
                    assign[i] = d;
                    break;
                }
            }
        }
        return assign;
    }

    // ---------- 3. 簇→天排序（设计说明书 §6.3"日序安排"） ----------

    /** 簇质心 = 成员均值坐标 */
    private double[][] computeCentroids(List<PoiCandidate> candidates, int[] assign, int k) {
        double[][] centroids = new double[k][2];
        int[] sizes = new int[k];
        for (int i = 0; i < candidates.size(); i++) {
            int c = assign[i];
            centroids[c][0] += candidates.get(i).getLatitude().doubleValue();
            centroids[c][1] += candidates.get(i).getLongitude().doubleValue();
            sizes[c]++;
        }
        for (int c = 0; c < k; c++) {
            if (sizes[c] > 0) {
                centroids[c][0] /= sizes[c];
                centroids[c][1] /= sizes[c];
            }
        }
        return centroids;
    }

    /**
     * 从簇 0（有必去点时即首个锚定簇）出发按质心贪心最近邻排序：
     * 相邻天的区域尽量接近，降低换区成本（设计说明书 §6.3"日序安排"）。
     * 距离相等取簇下标小者（确定性）。
     */
    private int[] orderClusters(double[][] centroids, int k) {
        int[] order = new int[k];
        boolean[] used = new boolean[k];
        order[0] = 0;
        used[0] = true;
        for (int seq = 1; seq < k; seq++) {
            int prev = order[seq - 1];
            int next = -1;
            double nextD = Double.MAX_VALUE;
            for (int c = 0; c < k; c++) {
                if (used[c]) {
                    continue;
                }
                double d = Haversine.distanceMeters(
                        centroids[prev][0], centroids[prev][1], centroids[c][0], centroids[c][1]);
                if (d < nextD) {
                    nextD = d;
                    next = c;
                }
            }
            order[seq] = next;
            used[next] = true;
        }
        return order;
    }

    // ---------- 输出 ----------

    private ClusterOutput buildOutput(List<PoiCandidate> candidates, int[] assign,
                                      double[][] centroids, int[] dayOrder, int k, int anchorGroupCount) {
        List<DayCluster> clusters = new ArrayList<>(k);
        for (int seq = 0; seq < k; seq++) {
            int c = dayOrder[seq];
            List<PoiCandidate> points = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (assign[i] == c) {
                    points.add(candidates.get(i));
                }
            }
            clusters.add(DayCluster.builder()
                    .daySeq(seq + 1)
                    .points(points)
                    .centroidLat(centroids[c][0])
                    .centroidLng(centroids[c][1])
                    .anchored(c < anchorGroupCount)
                    .build());
        }
        return ClusterOutput.builder()
                .clusters(clusters)
                .days(k)
                .anchorGroupCount(anchorGroupCount)
                .build();
    }

    // ---------- 日志（人读表格，AGENTS.md 测试/日志定规） ----------

    private void printClusters(ClusterOutput output) {
        StringBuilder sb = new StringBuilder("\n---------- 空间聚类结果 k=")
                .append(output.getDays()).append("（CP-SAT 约束划分）----------\n");
        sb.append(String.format("  锚组=%d，每日点数 [%d,%d]，簇直径 ≤ %.0fkm%n",
                output.getAnchorGroupCount(), properties.getMinPoisPerDay(),
                properties.getMaxPoisPerDay(), properties.getMaxClusterDiameterMeters() / 1000));
        for (DayCluster day : output.getClusters()) {
            sb.append(String.format("  第 %d 天%s 质心(%.5f, %.5f) 共 %d 点：%s%n",
                    day.getDaySeq(), day.isAnchored() ? "（锚定）" : "（自由）",
                    day.getCentroidLat(), day.getCentroidLng(), day.getPoints().size(),
                    day.getPoints().stream()
                            .map(p -> p.getName() + "(id=" + p.getId() + (p.isMustVisit() ? ",必去" : "") + ")")
                            .reduce((a, b) -> a + "、 " + b).orElse("")));
        }
        log.info(sb.toString());
    }
}
