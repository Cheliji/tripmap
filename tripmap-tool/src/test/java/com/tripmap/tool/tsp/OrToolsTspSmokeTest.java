package com.tripmap.tool.tsp;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * OR-Tools 5 节点 TSP 冒烟测试（任务 0.8）。
 *
 * <p>验证三件事：① 本地库可装载（Loader）；② 标准闭环 TSP 可求解；
 * ③ 解的几何正确性——5 个共线节点（x=0..4，成本为欧氏距离取整）的
 * 最优环游必为 0→1→2→3→4→0，总成本 8，此期望值可手工推导。</p>
 *
 * <p>该形态是设计说明书 4.2 节"零成本虚拟节点法"的退化原型：
 * 开放路径退化为标准 TSP 求解，任务 2.6 在此基础上加虚拟节点/起终点枚举、
 * 时间窗与终点偏好。</p>
 */
class OrToolsTspSmokeTest {

    @Test
    @DisplayName("5 节点共线 TSP：求出最优环游，总成本为 8 且 5 点各访问一次")
    void fiveNodeTspFindsOptimalTour() {
        // OR-Tools Java 依赖内嵌各平台 native 库，必须先装载
        Loader.loadNativeLibraries();

        // 5 个节点排布在 x 轴 0..4 处，成本矩阵为欧氏距离取整
        long[][] costMatrix = buildCollinearCostMatrix(5);

        // 单辆车、起点（闭环即终点）为节点 0
        RoutingIndexManager manager = new RoutingIndexManager(5, 1, 0);
        RoutingModel routing = new RoutingModel(manager);

        // 直接注册通勤矩阵（任务 2.6 将由高德通勤矩阵工具产出同构入参）
        int costCallbackIndex = routing.registerTransitMatrix(costMatrix);
        routing.setArcCostEvaluatorOfVehicle(costCallbackIndex, 0);

        RoutingSearchParameters searchParams = main.defaultRoutingSearchParameters()
                .toBuilder()
                // 首解策略：从起点反复走向最近未访问点（TSP 冒烟足够）
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .build();

        Assignment solution = routing.solveWithParameters(searchParams);
        assertNotNull(solution, "5 节点 TSP 必须可解");

        // 最优环游成本：0→1→2→3→4→0 = 1+1+1+1+4 = 8
        assertEquals(8L, solution.objectiveValue());

        // 按 nextVar 链表回溯完整路线（含回到起点的收尾节点）
        List<Integer> route = new ArrayList<>();
        long index = routing.start(0);
        while (!routing.isEnd(index)) {
            route.add(manager.indexToNode(index));
            index = solution.value(routing.nextVar(index));
        }
        route.add(manager.indexToNode(routing.end(0)));

        // 环游完整性：5 个互不相同节点 + 收尾的回到起点节点
        assertEquals(6, route.size());
        assertEquals(Set.of(0, 1, 2, 3, 4), new HashSet<>(route.subList(0, 5)));
        assertEquals(route.get(0), route.get(5), "闭环必须回到起点");
    }

    /**
     * 构造 n 个共线节点（x=0..n-1，y=0）的成本矩阵，元素为欧氏距离取整。
     */
    private static long[][] buildCollinearCostMatrix(int n) {
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                matrix[i][j] = Math.abs(i - j);
            }
        }
        return matrix;
    }
}
