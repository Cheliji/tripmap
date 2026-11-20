package com.tripmap.tool.cluster;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.cluster.config.ClusterProperties;
import com.tripmap.tool.cluster.dto.ClusterInput;
import com.tripmap.tool.cluster.dto.ClusterOutput;
import com.tripmap.tool.cluster.dto.DayCluster;
import com.tripmap.tool.poi.PoiCandidate;
import com.tripmap.tool.spi.ToolNames;
import com.tripmap.tool.spi.TripTool;
import com.tripmap.tool.util.Haversine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 空间聚类工具（任务 2.5，设计说明书 §6.2"空间聚类"）：
 * 把精选后的候选 POI 按地理位置分成 k（=行程天数）个簇，实现"一天一个区域"。
 *
 * <p><b>流程（四段管道，每段对应 TASKS 子任务）</b>：</p>
 * <ol>
 *   <li><b>必去点近邻归并 + 锚定</b>（2.5.3）：必去点两两 haversine 距离 ≤
 *       归并阈值的用并查集归为一组（传递闭包），每组占一天、组内成员强制同簇，
 *       该簇质心 = 组成员均值坐标且迭代中固定不动——该天围绕必去区域展开。
 *       不做"按用户意愿集中"（跨区强排同一天违背"一天一个区域"原则，
 *       2026-11 与用户确认后取消该分支）；</li>
 *   <li><b>K-Means</b>（2.5.1/2.5.2）：haversine 球面距离；剩余 k-g 个自由簇
 *       以固定种子做 k-means++ 初始质心抽样，保证同输入同输出；自由点分配到
 *       最近质心（距离相等取簇下标小者，确定性），自由簇质心按成员均值更新，
 *       空簇由"离本簇质心最远的自由点"确定性塞入兜底；</li>
 *   <li><b>簇平衡校正</b>（2.5.4）：簇间大小差 &gt; 阈值（默认 2）时，大簇中
 *       离质心最远的自由点移交"小簇"——接收方在小簇中取离被移点最近者，
 *       <b>且移交距离 ≤ maxBalanceMoveMeters（默认 15km）</b>，超距视为非相邻区域拒绝
 *       （真实数据验证细化，2026-11-19：全局最小簇可能是几十公里外的远郊簇，
 *       硬凑平衡会把市中心点拽进远郊天，违背"一天一个区域"；地理纯度优先，
 *       宁可不平衡标记 balanced=false，由上游补候选/替补解决）；锚定点永不移动
 *       （锚定优先于平衡）；</li>
 *   <li><b>簇→天排序</b>（2.5.5）：从首个锚定簇（无必去点时簇 0）出发按簇质心
 *       贪心最近邻编为 day 1..k，相邻天区域尽量接近、降低换区成本
 *       （设计说明书 §6.3"日序安排"）。</li>
 * </ol>
 *
 * <p><b>明确不做</b>：district_adcode 跨江跨山校正——设计说明书 §6.2 原文即
 * "交由通勤矩阵校验暴露"，MVP 不采集商圈字段（§2.2）。</p>
 *
 * <p>纯本地算法：零外部 API、零数据库依赖；距离矩阵 O(n²) 预计算（n≤40）。</p>
 */
@Component
public class SpatialClusterTool implements TripTool<ClusterInput, ClusterOutput> {

    private static final Logger log = LoggerFactory.getLogger(SpatialClusterTool.class);

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
        long seed = input.getSeed() != null ? input.getSeed() : properties.getSeed();

        // 距离矩阵一次性预计算（n≤40，O(n²) 廉价；迭代中只查表）
        double[][] dist = buildDistanceMatrix(candidates);

        // ---------- 1. 必去点近邻归并 → 锚定簇（2.5.3） ----------
        List<List<Integer>> anchorGroups = mergeAnchorGroups(candidates, dist);
        int g = anchorGroups.size();
        if (g > k) {
            throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                    String.format("必去景点归并后共 %d 组，超过行程天数 %d：每组各占一天，无法安排，"
                            + "请减少必去景点或增加天数", g, k));
        }
        int anchorPointCount = anchorGroups.stream().mapToInt(List::size).sum();
        if (n - anchorPointCount < k - g) {
            throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                    String.format("候选共 %d 个，其中必去点 %d 个已锚定 %d 天，剩余自由点 %d 个"
                                    + "不足填充剩余 %d 天，请补充候选或减少必去景点",
                            n, anchorPointCount, g, n - anchorPointCount, k - g));
        }

        // assign[i]：候选 i 所属簇下标；锚定点在此钉死，后续不再变更
        int[] assign = new int[n];
        Arrays.fill(assign, -1);
        for (int c = 0; c < g; c++) {
            for (int idx : anchorGroups.get(c)) {
                assign[idx] = c;
            }
        }
        List<Integer> freePoints = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (assign[i] < 0) {
                freePoints.add(i);
            }
        }

        // ---------- 2. K-Means（2.5.1/2.5.2） ----------
        // centroids[c] = {lat, lng}；锚定簇（0..g-1）质心固定，自由簇（g..k-1）随迭代更新
        double[][] centroids = initCentroids(candidates, anchorGroups, freePoints, k, dist, seed);
        int iterations = kMeans(candidates, dist, centroids, assign, freePoints, g, k);

        // ---------- 3. 簇平衡校正（2.5.4） ----------
        int movedPoints = rebalance(candidates, centroids, assign, freePoints, g, k);
        int[] sizes = clusterSizes(assign, k);
        int maxSize = Arrays.stream(sizes).max().orElse(0);
        int minSize = Arrays.stream(sizes).min().orElse(0);
        boolean balanced = maxSize - minSize <= properties.getBalanceThreshold();

        // ---------- 4. 簇→天排序（2.5.5） ----------
        int[] dayOrder = orderClusters(centroids, k);

        ClusterOutput output = buildOutput(candidates, assign, centroids, dayOrder,
                k, seed, iterations, balanced, movedPoints, g);
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

    // ---------- 2. K-Means ----------

    /**
     * 初始质心：锚定簇 = 锚组成员均值坐标（固定）；自由簇 = 固定种子 k-means++
     * 在自由点中抽样（距离平方加权，几何上拉开初始质心间距，收敛更稳）。
     */
    private double[][] initCentroids(List<PoiCandidate> candidates, List<List<Integer>> anchorGroups,
                                     List<Integer> freePoints, int k, double[][] dist, long seed) {
        double[][] centroids = new double[k][2];
        for (int c = 0; c < anchorGroups.size(); c++) {
            centroids[c] = meanCoords(candidates, anchorGroups.get(c));
        }
        int freeClusters = k - anchorGroups.size();
        if (freeClusters == 0) {
            return centroids;
        }
        Random random = new Random(seed);
        List<Integer> chosen = new ArrayList<>();
        for (int c = anchorGroups.size(); c < k; c++) {
            int next;
            if (chosen.isEmpty() && anchorGroups.isEmpty()) {
                // 无任何锚点：第一个自由质心种子随机取一个自由点
                next = freePoints.get(random.nextInt(freePoints.size()));
            } else {
                // k-means++：与已选自由质心和全部锚定质心的最近距离平方为权重——
                // 锚定质心参与加权，避免自由簇初始质心落在锚点附近导致远簇被劈开
                next = pickKMeansPlusPlus(candidates, freePoints, chosen, centroids,
                        anchorGroups.size(), dist, random);
            }
            chosen.add(next);
            centroids[c] = coords(candidates.get(next));
        }
        return centroids;
    }

    /**
     * k-means++ 抽样：权重 = min(到已选自由质心距离², 到最近锚定质心距离²)；
     * 权重和为 0（点重合）时按序取未选点兜底，保证确定性。
     */
    private int pickKMeansPlusPlus(List<PoiCandidate> candidates, List<Integer> freePoints,
                                   List<Integer> chosen, double[][] centroids, int anchorCount,
                                   double[][] dist, Random random) {
        double total = 0;
        double[] weights = new double[freePoints.size()];
        for (int i = 0; i < freePoints.size(); i++) {
            int p = freePoints.get(i);
            double minD = Double.MAX_VALUE;
            for (int c : chosen) {
                minD = Math.min(minD, dist[p][c]);
            }
            for (int c = 0; c < anchorCount; c++) {
                minD = Math.min(minD, pointToCentroid(candidates.get(p), centroids[c]));
            }
            weights[i] = minD * minD;
            total += weights[i];
        }
        if (total <= 0) {
            for (int p : freePoints) {
                if (!chosen.contains(p)) {
                    return p;
                }
            }
            return freePoints.get(0);
        }
        double r = random.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r < acc) {
                return freePoints.get(i);
            }
        }
        return freePoints.get(weights.length - 1);
    }

    /**
     * K-Means 迭代：自由点分配到最近质心（距离相等取簇下标小者）→
     * 自由簇质心按成员均值更新 → 空簇确定性兜底；分配不再变化或达上限时收敛。
     *
     * @return 实际迭代轮数
     */
    private int kMeans(List<PoiCandidate> candidates, double[][] dist, double[][] centroids,
                       int[] assign, List<Integer> freePoints, int anchorCount, int k) {
        int iterations = 0;
        for (int iter = 1; iter <= properties.getMaxIterations(); iter++) {
            iterations = iter;
            boolean changed = false;
            for (int p : freePoints) {
                int best = nearestCentroid(candidates.get(p), centroids);
                if (assign[p] != best) {
                    assign[p] = best;
                    changed = true;
                }
            }
            updateFreeCentroids(candidates, centroids, assign, freePoints, anchorCount, k);
            // 空簇兜底若移动了点位，视同分配有变化，需再跑一轮分配
            changed |= rescueEmptyClusters(candidates, centroids, assign, freePoints, anchorCount, k);
            if (!changed) {
                break;
            }
        }
        return iterations;
    }

    /** 最近质心（锚定簇质心也参与竞争：自由点离必去点近就进锚定簇）；距离相等取簇下标小者 */
    private int nearestCentroid(PoiCandidate point, double[][] centroids) {
        int best = 0;
        double bestD = Haversine.distanceMeters(
                point.getLatitude().doubleValue(), point.getLongitude().doubleValue(),
                centroids[0][0], centroids[0][1]);
        for (int c = 1; c < centroids.length; c++) {
            double d = Haversine.distanceMeters(
                    point.getLatitude().doubleValue(), point.getLongitude().doubleValue(),
                    centroids[c][0], centroids[c][1]);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** 自由簇质心 = 成员均值坐标；锚定簇不动 */
    private void updateFreeCentroids(List<PoiCandidate> candidates, double[][] centroids,
                                     int[] assign, List<Integer> freePoints, int anchorCount, int k) {
        for (int c = anchorCount; c < k; c++) {
            List<Integer> members = membersOf(assign, c);
            if (!members.isEmpty()) {
                centroids[c] = meanCoords(candidates, members);
            }
        }
    }

    /**
     * 空簇兜底：自由簇迭代后为空时，确定性地把"离本簇质心最远"的自由点移入
     * （平局取输入下标小者）；被移点原簇保证仍非空（只从成员 ≥2 的簇里拿）。
     *
     * @return 是否有点位被移动
     */
    private boolean rescueEmptyClusters(List<PoiCandidate> candidates,
                                        double[][] centroids, int[] assign,
                                        List<Integer> freePoints, int anchorCount, int k) {
        boolean rescued = false;
        for (int c = anchorCount; c < k; c++) {
            while (membersOf(assign, c).isEmpty()) {
                int[] sizes = clusterSizes(assign, k);
                int victim = -1;
                double victimDist = -1;
                for (int p : freePoints) {
                    if (assign[p] == c || sizes[assign[p]] < 2) {
                        continue;
                    }
                    double d = pointToCentroid(candidates.get(p), centroids[assign[p]]);
                    if (d > victimDist) {
                        victimDist = d;
                        victim = p;
                    }
                }
                if (victim < 0) {
                    // 极端情况：所有自由点都是独占簇成员，无法再拆（前置校验已尽量拦截）
                    throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                            "聚类出现无法填充的空簇，请补充候选景点或调整天数");
                }
                assign[victim] = c;
                rescued = true;
                log.warn("K-Means 空簇兜底：候选 {} 移入空簇 {}", candidates.get(victim).getId(), c);
                updateFreeCentroids(candidates, centroids, assign, freePoints, anchorCount, k);
            }
        }
        return rescued;
    }

    // ---------- 3. 簇平衡校正（2.5.4） ----------

    /**
     * 簇间大小差 &gt; 阈值时，大簇中离质心最远的自由点移交"小簇"；
     * 锚定点永不移动（锚定优先于平衡）。
     *
     * <p><b>移交规则（真实数据验证后细化，2026-11-19）</b>：设计说明书 §6.2 原文是
     * "移交小簇"，最初实现取全局最小簇——真实数据暴露出缺陷：最小簇可能是几十公里外
     * 的远郊簇（如淳安），为凑平衡会把市中心点拽进远郊天（"拱墅商场 + 淳安景点同一天"），
     * 违背"一天一个区域"。细化为：在"小簇"（大小 ≤ 最小簇 + 阈值）中选离被移点最近者，
     * 且<b>移交距离 ≤ maxBalanceMoveMeters</b>（默认 15km，配置化）——超过即视为
     * 非相邻区域，拒绝移交。地理纯度优先于簇大小：区域完整性由硬约束保证，簇偏小时
     * 由上游补候选/替补解决（设计说明书 §6.4），不靠跨区硬凑。</p>
     *
     * <p>每轮至多移动 1 个点，移动后重算受影响的自由簇质心；轮数上限 n 保证收敛。</p>
     *
     * @return 实际移动的点数
     */
    private int rebalance(List<PoiCandidate> candidates, double[][] centroids,
                          int[] assign, List<Integer> freePoints, int anchorCount, int k) {
        int moved = 0;
        // 上限 n 轮：每轮至多移动 1 个点且总点数有限，必然收敛
        for (int round = 0; round < candidates.size(); round++) {
            int[] sizes = clusterSizes(assign, k);
            int minC = argMin(sizes);
            int maxC = argMax(sizes);
            if (sizes[maxC] - sizes[minC] <= properties.getBalanceThreshold()) {
                break;
            }
            boolean movedThisRound = false;
            // 按簇大小降序尝试大簇：找到一个"相邻区域"移交即执行；全无则终止
            for (int donor : clustersBySizeDesc(sizes)) {
                if (sizes[donor] - sizes[minC] <= properties.getBalanceThreshold()) {
                    break;
                }
                if (!hasMovablePoint(assign, freePoints, donor)) {
                    continue;
                }
                int farthest = farthestMovablePoint(candidates, centroids, assign, freePoints, donor);
                int recipient = nearestSmallCluster(candidates.get(farthest), sizes,
                        minC, centroids, donor, k);
                // 移交距离超过阈值 → 非相邻区域，拒绝；尝试下一个大簇
                if (pointToCentroid(candidates.get(farthest), centroids[recipient])
                        > properties.getMaxBalanceMoveMeters()) {
                    continue;
                }
                assign[farthest] = recipient;
                moved++;
                log.debug("簇平衡：候选 {} 由簇 {}（{} 点）移交簇 {}（{} 点，相距 {}m）",
                        candidates.get(farthest).getId(), donor, sizes[donor], recipient, sizes[recipient],
                        Math.round(pointToCentroid(candidates.get(farthest), centroids[recipient])));
                updateFreeCentroids(candidates, centroids, assign, freePoints, anchorCount, k);
                movedThisRound = true;
                break;
            }
            if (!movedThisRound) {
                log.warn("簇平衡校正终止：无可移交的相邻小簇，保持地理纯度（接受不平衡，balanced=false）");
                break;
            }
        }
        return moved;
    }

    private boolean hasMovablePoint(int[] assign, List<Integer> freePoints, int cluster) {
        for (int p : freePoints) {
            if (assign[p] == cluster) {
                return true;
            }
        }
        return false;
    }

    /**
     * 移交目标："小簇"（大小 ≤ 最小簇 + 阈值，接收后不破坏平衡带）中离被移点
     * 最近的簇；距离相等取簇下标小者（确定性）。最小簇本身必在候选内，不会空集。
     */
    private int nearestSmallCluster(PoiCandidate point, int[] sizes, int minCluster,
                                    double[][] centroids, int donor, int k) {
        int recipient = minCluster;
        double best = pointToCentroid(point, centroids[minCluster]);
        for (int c = 0; c < k; c++) {
            if (c == donor || sizes[c] > sizes[minCluster] + properties.getBalanceThreshold()) {
                continue;
            }
            double d = pointToCentroid(point, centroids[c]);
            if (d < best) {
                best = d;
                recipient = c;
            }
        }
        return recipient;
    }

    /** 簇内离质心最远的自由点（平局取输入下标小者，确定性） */
    private int farthestMovablePoint(List<PoiCandidate> candidates, double[][] centroids,
                                     int[] assign, List<Integer> freePoints, int cluster) {
        int farthest = -1;
        double farthestDist = -1;
        for (int p : freePoints) {
            if (assign[p] != cluster) {
                continue;
            }
            double d = pointToCentroid(candidates.get(p), centroids[cluster]);
            if (d > farthestDist) {
                farthestDist = d;
                farthest = p;
            }
        }
        return farthest;
    }

    // ---------- 4. 簇→天排序（2.5.5） ----------

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
                                      double[][] centroids, int[] dayOrder,
                                      int k, long seed, int iterations, boolean balanced,
                                      int movedPoints, int anchorGroupCount) {
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
                .seed(seed)
                .iterations(iterations)
                .balanced(balanced)
                .movedPoints(movedPoints)
                .anchorGroupCount(anchorGroupCount)
                .build();
    }

    // ---------- 小工具 ----------

    private double[] coords(PoiCandidate p) {
        return new double[]{p.getLatitude().doubleValue(), p.getLongitude().doubleValue()};
    }

    private double[] meanCoords(List<PoiCandidate> candidates, List<Integer> memberIdx) {
        double lat = 0;
        double lng = 0;
        for (int idx : memberIdx) {
            lat += candidates.get(idx).getLatitude().doubleValue();
            lng += candidates.get(idx).getLongitude().doubleValue();
        }
        return new double[]{lat / memberIdx.size(), lng / memberIdx.size()};
    }

    private double pointToCentroid(PoiCandidate p, double[] centroid) {
        return Haversine.distanceMeters(
                p.getLatitude().doubleValue(), p.getLongitude().doubleValue(),
                centroid[0], centroid[1]);
    }

    private List<Integer> membersOf(int[] assign, int cluster) {
        List<Integer> members = new ArrayList<>();
        for (int i = 0; i < assign.length; i++) {
            if (assign[i] == cluster) {
                members.add(i);
            }
        }
        return members;
    }

    private int[] clusterSizes(int[] assign, int k) {
        int[] sizes = new int[k];
        for (int a : assign) {
            sizes[a]++;
        }
        return sizes;
    }

    private int argMin(int[] sizes) {
        int best = 0;
        for (int i = 1; i < sizes.length; i++) {
            if (sizes[i] < sizes[best]) {
                best = i;
            }
        }
        return best;
    }

    private int argMax(int[] sizes) {
        int best = 0;
        for (int i = 1; i < sizes.length; i++) {
            if (sizes[i] > sizes[best]) {
                best = i;
            }
        }
        return best;
    }

    /** 簇下标按大小降序（平局下标小者在前），冒泡即可（k≤7） */
    private int[] clustersBySizeDesc(int[] sizes) {
        int[] order = new int[sizes.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        for (int i = 0; i < order.length; i++) {
            for (int j = i + 1; j < order.length; j++) {
                if (sizes[order[j]] > sizes[order[i]]) {
                    int tmp = order[i];
                    order[i] = order[j];
                    order[j] = tmp;
                }
            }
        }
        return order;
    }

    // ---------- 日志（人读表格，AGENTS.md 测试/日志定规） ----------

    private void printClusters(ClusterOutput output) {
        StringBuilder sb = new StringBuilder("\n---------- 空间聚类结果 k=")
                .append(output.getDays()).append(" ----------\n");
        sb.append(String.format("  种子=%d，迭代 %d 轮，锚组 %d 个，平衡移动 %d 点，平衡达标=%s%n",
                output.getSeed(), output.getIterations(), output.getAnchorGroupCount(),
                output.getMovedPoints(), output.isBalanced() ? "是" : "否"));
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
