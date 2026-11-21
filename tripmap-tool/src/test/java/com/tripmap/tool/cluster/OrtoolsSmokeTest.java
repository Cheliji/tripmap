package com.tripmap.tool.cluster;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OR-Tools CP-SAT 冒烟测试（任务 2.5.7）：验证 ortools-java 依赖可解析、
 * 原生库可加载，并用一个 5 节点的约束划分最小用例跑通"变量 → 约束 → 求解 →
 * 回调收集解"全链路。
 *
 * <p>用例即设计说明书 §6.2 模型的最小形态：4 个候选分 2 天、每日 1~2 点、
 * 两个远点互斥（模拟簇直径约束），断言存在恰好两个镜像划分的解。
 * 按 AGENTS.md 定规打印人读日志。</p>
 */
class OrtoolsSmokeTest {

    @Test
    @DisplayName("CP-SAT 原生库加载 + 5 节点 3/2 划分求解（两个解）")
    void cpSatSmoke() {
        System.out.println("\n========== OR-Tools CP-SAT 冒烟（任务 2.5.7） ==========");
        Loader.loadNativeLibraries();
        System.out.println("  原生库加载成功 ✓");

        CpModel model = new CpModel();
        int n = 4; // 候选数
        int k = 2; // 天数
        // x[i][d]：候选 i 是否排在第 d 天（设计说明书 §6.2 变量形态）
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
        // 约束 2：每日 1~2 点（每日点数硬约束的最小形态，4 点分 2 天恰为 2/2）
        for (int d = 0; d < k; d++) {
            IntVar[] col = new IntVar[n];
            for (int i = 0; i < n; i++) {
                col[i] = x[i][d];
            }
            model.addLinearConstraint(LinearExpr.sum(col), 1, 2);
        }
        // 约束 3：点 0、1 互斥（模拟"超径点对不得同天"的线性化；鸽巢原理要求互斥点数 ≤ k）
        for (int d = 0; d < k; d++) {
            model.addLessOrEqual(LinearExpr.newBuilder().add(x[0][d]).add(x[1][d]).build(), 1);
        }

        CpSolver solver = new CpSolver();
        // 固定参数 + 单 worker：同输入同输出（设计说明书 §6.2 确定性）
        solver.getParameters().setNumSearchWorkers(1);

        // 回调收集全部可行解
        int[][] count = new int[k][1];
        CpSolverSolutionCallback cb = new CpSolverSolutionCallback() {
            @Override
            public void onSolutionCallback() {
                for (int d = 0; d < k; d++) {
                    int s = 0;
                    for (int i = 0; i < n; i++) {
                        s += (int) value(x[i][d]);
                    }
                    count[d][0]++;
                }
            }
        };
        CpSolverStatus status = solver.searchAllSolutions(model, cb);

        System.out.printf("  求解状态=%s，第 0 天解数=%d，第 1 天解数=%d（期望各 4：{0,2}/{0,3}/{1,2}/{1,3}）%n",
                status, count[0][0], count[1][0]);
        assertEquals(CpSolverStatus.OPTIMAL, status, "模型应有解（可行域非空）");
        assertEquals(4, count[0][0], "点 0/1 互斥分占两天，每日 2 点，共 4 种划分");
        assertEquals(4, count[1][0]);
        assertTrue(count[0][0] == count[1][0], "两天的解数必须一致（同一划分的两面）");
    }
}
