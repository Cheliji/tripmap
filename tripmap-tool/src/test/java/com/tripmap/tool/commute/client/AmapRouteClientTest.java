package com.tripmap.tool.commute.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.tool.amap.AmapClient;
import com.tripmap.tool.commute.CommuteMode;
import com.tripmap.tool.commute.config.CommuteMatrixProperties;
import com.tripmap.tool.commute.dto.CommutePairMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 高德路径规划 v5 业务封装单测（任务 2.4.1）。
 *
 * <p>{@link AmapClient} 走 Mockito 桩（HTTP 层行为在 AmapClientTest 已覆盖），
 * 本测试聚焦参数组装（路径、citycode、show_fields、strategy）与响应解析容错：
 * 多方案取第一条、cost 对象/字符串兼容、空方案=不可达、耗时缺失=不可达。</p>
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、请求参数与解析结果，console 可直接人工 review。</p>
 */
class AmapRouteClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AmapClient amapClient = mock(AmapClient.class);
    private final CommuteMatrixProperties properties = new CommuteMatrixProperties();
    private final AmapRouteClient routeClient = new AmapRouteClient(amapClient, properties);

    private String lastPath;
    private Map<String, String> lastParams;

    @BeforeEach
    void setUp() {
        // 默认桩：任何调用返回不可达，各用例再覆盖具体响应；
        // 记录路径与参数用 invocation.getArgument（ArgumentCaptor.capture() 不能用于 thenAnswer）
        when(amapClient.get(anyString(), any()))
                .thenAnswer(invocation -> {
                    lastPath = invocation.getArgument(0);
                    lastParams = invocation.getArgument(1);
                    return MAPPER.readTree("{\"route\":{\"paths\":[],\"transits\":[]}}");
                });
    }

    // ---------- 辅助 ----------

    private JsonNode drivingResponse(String duration, String distance) throws Exception {
        return MAPPER.readTree("{\"status\":\"1\",\"infocode\":\"10000\",\"route\":{" +
                "\"paths\":[{\"distance\":\"" + distance + "\",\"cost\":{\"duration\":\"" + duration + "\"}}]}}");
    }

    private JsonNode transitResponseV5(String duration, String fee) throws Exception {
        return MAPPER.readTree("{\"status\":\"1\",\"infocode\":\"10000\",\"route\":{" +
                "\"transits\":[{\"cost\":{\"duration\":\"" + duration + "\",\"transit_fee\":\"" + fee + "\"}," +
                "\"distance\":\"13882\"}]}}");
    }

    /** v3 兼容：cost 是票价字符串，耗时平铺在 transits[].duration（amap-route-api.md §3.4） */
    private JsonNode transitResponseV3Style(String duration) throws Exception {
        return MAPPER.readTree("{\"status\":\"1\",\"infocode\":\"10000\",\"route\":{" +
                "\"transits\":[{\"cost\":\"4.0\",\"duration\":\"" + duration + "\"}]}}");
    }

    private void printCase(String scenario, String path, Map<String, String> params,
                           CommutePairMetric metric) {
        System.out.println("\n========== 场景：" + scenario + " ==========");
        System.out.println("  请求路径: " + path);
        params.forEach((k, v) -> System.out.println("    " + k + " = " + v));
        System.out.printf("  解析结果: 耗时=%s 秒, 距离=%s 米%n",
                metric.getDurationSeconds() == null ? "不可达" : metric.getDurationSeconds(),
                metric.getDistanceMeters() == null ? "-" : metric.getDistanceMeters());
    }

    private CommutePairMetric queryDriving(JsonNode response) throws Exception {
        when(amapClient.get(anyString(), any())).thenAnswer(invocation -> {
            lastPath = invocation.getArgument(0);
            lastParams = invocation.getArgument(1);
            return response;
        });
        CommutePairMetric metric = routeClient.query(CommuteMode.DRIVING, null,
                new BigDecimal("120.144700"), new BigDecimal("30.240900"),
                new BigDecimal("120.101700"), new BigDecimal("30.242300"));
        printCase("驾车通勤查询", lastPath, lastParams, metric);
        return metric;
    }

    // ---------- 参数组装 ----------

    @Test
    void 驾车请求路径与参数正确() throws Exception {
        queryDriving(drivingResponse("2644", "27558"));

        assertEquals(AmapRouteClient.PATH_DRIVING, lastPath);
        assertEquals("120.1447,30.2409", lastParams.get("origin"), "坐标经度在前、去尾零");
        assertEquals("120.1017,30.2423", lastParams.get("destination"));
        assertEquals("cost", lastParams.get("show_fields"));
        assertEquals("32", lastParams.get("strategy"), "驾车默认策略 32（高德推荐）");
        assertNull(lastParams.get("city1"), "驾车不需要 citycode");
    }

    @Test
    void 公交请求必传citycode且只取最优方案() throws Exception {
        when(amapClient.get(anyString(), any())).thenAnswer(invocation -> {
            lastPath = invocation.getArgument(0);
            lastParams = invocation.getArgument(1);
            return transitResponseV5("5271", "4.0");
        });
        CommutePairMetric metric = routeClient.query(CommuteMode.BUS, "0571",
                new BigDecimal("120.144700"), new BigDecimal("30.240900"),
                new BigDecimal("120.101700"), new BigDecimal("30.242300"));
        printCase("公交通勤查询（杭州 0571）", lastPath, lastParams, metric);

        assertEquals(AmapRouteClient.PATH_TRANSIT, lastPath);
        assertEquals("0571", lastParams.get("city1"), "公交 city1 必传且只认 citycode");
        assertEquals("0", lastParams.get("strategy"), "公交默认策略 0（推荐模式）");
        assertEquals("1", lastParams.get("AlternativeRoute"));
        assertEquals("cost", lastParams.get("show_fields"));
    }

    // ---------- 响应解析 ----------

    @Test
    void 驾车解析cost对象取总耗时与距离() throws Exception {
        CommutePairMetric metric = queryDriving(drivingResponse("2644", "27558"));
        assertEquals(2644L, metric.getDurationSeconds());
        assertEquals(27558L, metric.getDistanceMeters());
    }

    @Test
    void 公交解析cost对象取总耗时() throws Exception {
        when(amapClient.get(anyString(), any())).thenAnswer(invocation -> {
            lastPath = invocation.getArgument(0);
            lastParams = invocation.getArgument(1);
            return transitResponseV5("5271", "4.0");
        });
        CommutePairMetric metric = routeClient.query(CommuteMode.BUS, "0571",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN);
        printCase("公交 v5 解析（cost 对象）", lastPath, lastParams, metric);
        assertEquals(5271L, metric.getDurationSeconds(), "总耗时含候车与步行");
        assertEquals(13882L, metric.getDistanceMeters());
    }

    @Test
    void 公交兼容v3形态cost为字符串时回退平铺duration() throws Exception {
        when(amapClient.get(anyString(), any())).thenAnswer(invocation -> {
            lastPath = invocation.getArgument(0);
            lastParams = invocation.getArgument(1);
            return transitResponseV3Style("3000");
        });
        CommutePairMetric metric = routeClient.query(CommuteMode.BUS, "0571",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN);
        printCase("公交 v3 兼容（cost 字符串 → transits[].duration）",
                lastPath, lastParams, metric);
        assertEquals(3000L, metric.getDurationSeconds());
    }

    @Test
    void 空方案列表返回不可达不抛异常() throws Exception {
        CommutePairMetric metric = queryDriving(MAPPER.readTree(
                "{\"route\":{\"paths\":[]}}"));
        assertNull(metric.getDurationSeconds(), "paths 为空 = 不可达");
        assertNull(metric.getDistanceMeters());
    }

    @Test
    void 耗时字段缺失返回不可达不编造() throws Exception {
        CommutePairMetric metric = queryDriving(MAPPER.readTree(
                "{\"route\":{\"paths\":[{\"distance\":\"100\",\"cost\":{}}]}}"));
        assertNull(metric.getDurationSeconds(), "cost.duration 缺失 = 不可达，禁止编造");
    }

    @Test
    void 坐标格式化经度在前且最多6位小数() {
        String coord = AmapRouteClient.formatCoord(
                new BigDecimal("120.144712345"), new BigDecimal("30.24"));
        System.out.println("\n========== 场景：坐标格式化 ==========");
        System.out.printf("  输入: lng=120.144712345, lat=30.24 → 输出: %s%n", coord);
        assertEquals("120.144712,30.24", coord, "6 位小数截断（HALF_UP）+ 去尾零");
    }
}
