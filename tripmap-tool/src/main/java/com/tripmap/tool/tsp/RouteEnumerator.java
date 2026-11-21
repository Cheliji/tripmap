package com.tripmap.tool.tsp;

/**
 * 日内双开放最短路径精确枚举器（任务 2.6.1 / 2.6.2，设计说明书 §6.3）。
 *
 * <p>纯 Java 全排列精确枚举，零 native 依赖。输入规模由空间聚类硬约束保证
 * 每日 1～7 点（本类不感知聚类，仅按传入 n 枚举）。</p>
 *
 * <p><b>为何枚举 n! 而非 n!/2 去对称</b>：通勤矩阵对称 ⇒ 反向路径总通勤相等；
 * 但时间窗/午餐禁游是<b>方向相关</b>的——同一串点正向可行、反向可能因到访时刻
 * 不同而不可行（或等待不同）。"双开放"起终点自由，正反两向是两条不同的可行
 * 日程，必须各自评估。n≤7 时 7! = 5,040 条，毫秒级，无需去对称优化
 * （任务 2.6.1 的 n!/2 是对纯通勤场景的量级描述，见设计说明书 §6.3 求解实现）。</p>
 *
 * <p><b>剪枝</b>（2.6.2）：按排列顺序增量累计到访时刻——到达早于开放则等到开放
 * （计入等待），游玩区间与午餐窗口相交则整体顺延到午餐结束（计入等待），
 * 离开晚于闭馆或当日闭馆立即剪枝；缺失通勤边（-1）同样剪枝。</p>
 *
 * <p><b>最优性</b>：目标 = 总通勤时间最小（数学最优）；同通勤再比总等待
 * （人读更友好），仍相同取枚举先生成者（按节点下标升序优先 ⇒ 确定性）。</p>
 */
final class RouteEnumerator {

    private RouteEnumerator() {
    }

    /**
     * 求解。时间单位统一为秒（通勤矩阵原生单位）。
     *
     * @param commuteSeconds   n×n 通勤矩阵（秒），-1 = 缺失
     * @param visitSeconds     每点游玩时长（秒，默认值已补齐）
     * @param openSegmentsSeconds 每点当日开放时段，扁平化 [open0,close0,open1,close1,...]（秒）；
     *                            空数组 = 闭馆日；[0,86400] = 24h
     * @param startSeconds     当日出发时刻（秒）
     * @param lunchStartSeconds 午餐禁游开始（秒）
     * @param lunchEndSeconds   午餐禁游结束（秒）；与 lunchStart 相等表示禁用
     * @return 最优解；全部排列不可行返回 null
     */
    static SolveResult solve(long[][] commuteSeconds, int[] visitSeconds, int[][] openSegmentsSeconds,
                             int startSeconds, int lunchStartSeconds, int lunchEndSeconds) {
        int n = visitSeconds.length;
        State state = new State(n);
        state.commute = commuteSeconds;
        state.visitSec = visitSeconds;
        state.openSec = openSegmentsSeconds;
        state.startSec = startSeconds;
        state.lunchStartSec = lunchStartSeconds;
        state.lunchEndSec = lunchEndSeconds;
        state.lunchEnabled = lunchStartSeconds != lunchEndSeconds;

        dfs(state, 0, -1, startSeconds, 0L, 0L);
        if (state.bestOrder == null) {
            return null;
        }
        return buildResult(state);
    }

    // ---------- 深度优先 + 剪枝 ----------

    private static void dfs(State s, int depth, int prevIndex, long prevDepartSec,
                            long commuteSum, long waitSum) {
        if (depth == s.n) {
            // 比较：总通勤升序 → 总等待升序 → 生成顺序（DFS 按下标升序展开，先到先得 ⇒ 确定性）
            if (s.bestOrder == null
                    || commuteSum < s.bestCommute
                    || (commuteSum == s.bestCommute && waitSum < s.bestWait)) {
                s.bestOrder = s.order.clone();
                s.bestCommute = commuteSum;
                s.bestWait = waitSum;
            }
            return;
        }
        for (int next = 0; next < s.n; next++) {
            if (s.used[next]) {
                continue;
            }
            Step st = step(s, prevIndex, prevDepartSec, next);
            if (st == null) {
                continue; // 时间窗 / 午餐 / 缺失边剪枝
            }
            s.order[depth] = next;
            s.used[next] = true;
            dfs(s, depth + 1, next, st.departSec, commuteSum + st.commuteSec, waitSum + st.waitSec);
            s.used[next] = false;
        }
    }

    /**
     * 计算"排在 prevIndex 之后访问 next"的到离/等待/通勤。
     * prevIndex = -1 表示 next 为当日首站（无通勤，到达锚点 = 出发时刻）。
     *
     * @return 步进结果；不可行（缺失边/闭馆/午餐顺延后仍超闭馆）返回 null
     */
    private static Step step(State s, int prevIndex, long prevDepartSec, int next) {
        long commuteSec = prevIndex < 0 ? 0L : s.commute[prevIndex][next];
        if (commuteSec < 0) {
            return null; // 缺失通勤边，该顺序不可行
        }
        long arriveSec = (prevIndex < 0 ? s.startSec : prevDepartSec) + commuteSec;
        int visit = s.visitSec[next];
        int[] segments = s.openSec[next];

        // 1) 开放等待：找最早可开始的时段（未到开放等到开放）
        long startSec = earliestStart(segments, arriveSec, visit);
        if (startSec < 0) {
            return null; // 当日闭馆 / 到访过晚，剪枝
        }

        // 2) 午餐禁游：游玩区间 [start, start+visit) 不得与 [lunchStart, lunchEnd) 相交
        if (s.lunchEnabled && startSec < s.lunchEndSec && startSec + visit > s.lunchStartSec) {
            long afterLunch = earliestStart(segments, s.lunchEndSec, visit);
            if (afterLunch < 0) {
                return null; // 顺延到午餐后仍超闭馆，剪枝
            }
            startSec = afterLunch;
        }

        return new Step(commuteSec, startSec, startSec + visit, startSec - arriveSec);
    }

    /**
     * 在给定时段列表内，找不早于 {@code lowerBound} 的最早可行开始时刻。
     * 时段按 open 升序（open_hours 校验器保证），首个可行即最早。
     *
     * @param segments 扁平时段（秒）；空数组 = 闭馆
     * @return 可行开始秒数；无可行返回 -1
     */
    private static long earliestStart(int[] segments, long lowerBound, int visitSec) {
        for (int i = 0; i + 1 < segments.length; i += 2) {
            long open = segments[i];
            long close = segments[i + 1];
            long start = Math.max(lowerBound, open);
            if (start + visitSec <= close) {
                return start;
            }
        }
        return -1;
    }

    // ---------- 最优解还原（按最优顺序重放一遍，产出逐站明细） ----------

    private static SolveResult buildResult(State s) {
        int n = s.n;
        long[] arrive = new long[n];
        long[] depart = new long[n];
        long[] wait = new long[n];
        long[] commute = new long[n];
        long prevDepart = s.startSec;
        for (int pos = 0; pos < n; pos++) {
            int node = s.bestOrder[pos];
            int prev = pos == 0 ? -1 : s.bestOrder[pos - 1];
            Step st = step(s, prev, prevDepart, node);
            // bestOrder 必可行（记录前已通过剪枝），此处 step 不会返回 null
            commute[pos] = st.commuteSec;
            arrive[pos] = st.startSec;
            depart[pos] = st.departSec;
            wait[pos] = st.waitSec;
            prevDepart = st.departSec;
        }
        SolveResult r = new SolveResult();
        r.order = s.bestOrder.clone();
        r.commuteSeconds = s.bestCommute;
        r.waitSeconds = s.bestWait;
        r.arriveSeconds = arrive;
        r.departSeconds = depart;
        r.waitSecondsPerNode = wait;
        r.commuteSecondsPerNode = commute;
        return r;
    }

    // ---------- 内部结构 ----------

    /** 一次"到达→游玩→离开"步进 */
    private static final class Step {
        final long commuteSec;
        final long startSec;
        final long departSec;
        final long waitSec;

        Step(long commuteSec, long startSec, long departSec, long waitSec) {
            this.commuteSec = commuteSec;
            this.startSec = startSec;
            this.departSec = departSec;
            this.waitSec = waitSec;
        }
    }

    /** 枚举过程态（递归共享，避免逐层传参） */
    private static final class State {
        final int n;
        long[][] commute;
        int[] visitSec;
        int[][] openSec;
        int startSec;
        int lunchStartSec;
        int lunchEndSec;
        boolean lunchEnabled;

        final int[] order;
        final boolean[] used;
        int[] bestOrder;
        long bestCommute;
        long bestWait;

        State(int n) {
            this.n = n;
            this.order = new int[n];
            this.used = new boolean[n];
        }
    }

    /** 求解结果（包内可见，供 {@link RouteSolveTool} 与同包测试消费） */
    static final class SolveResult {
        /** 访问顺序（节点下标，非 POI ID） */
        int[] order;
        /** 总通勤秒数 */
        long commuteSeconds;
        /** 总等待秒数 */
        long waitSeconds;
        /** 每站到达秒数（与 order 对齐） */
        long[] arriveSeconds;
        /** 每站离开秒数 */
        long[] departSeconds;
        /** 每站等待秒数 */
        long[] waitSecondsPerNode;
        /** 每站进入通勤秒数（首站 0） */
        long[] commuteSecondsPerNode;
    }
}
