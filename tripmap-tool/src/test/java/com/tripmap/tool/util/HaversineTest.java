package com.tripmap.tool.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Haversine 球面距离单测（任务 2.5.1）。
 *
 * <p>按 AGENTS.md 定规：打印场景、输入坐标、计算结果与参照值，console 可直接人工核对。</p>
 */
class HaversineTest {

    private void printDistance(String scenario, double lat1, double lon1, double lat2, double lon2,
                               double resultMeters, String reference) {
        System.out.println("""
                
                ========== 场景：%s ==========""".formatted(scenario));
        System.out.printf("  点 1     : (%.6f, %.6f)%n", lat1, lon1);
        System.out.printf("  点 2     : (%.6f, %.6f)%n", lat2, lon2);
        System.out.printf("  计算结果 : %.1f 米%n", resultMeters);
        System.out.printf("  参照值   : %s%n", reference);
    }

    @Test
    @DisplayName("同一坐标距离为 0")
    void samePointIsZero() {
        double d = Haversine.distanceMeters(30.25, 120.15, 30.25, 120.15);
        printDistance("同一点", 30.25, 120.15, 30.25, 120.15, d, "0 米");
        assertEquals(0.0, d, 1e-9);
    }

    @Test
    @DisplayName("对称性：A→B 与 B→A 距离一致")
    void symmetric() {
        double ab = Haversine.distanceMeters(30.2586, 120.1517, 30.2534, 120.1432);
        double ba = Haversine.distanceMeters(30.2534, 120.1432, 30.2586, 120.1517);
        printDistance("对称性（断桥残雪 ↔ 平湖秋月）", 30.2586, 120.1517, 30.2534, 120.1432, ab,
                "B→A = " + String.format("%.1f", ba) + " 米（应完全相等）");
        assertEquals(ab, ba, 1e-9);
    }

    @Test
    @DisplayName("纬度差 1° ≈ 111.2 km（教科书参照值）")
    void oneDegreeLatitude() {
        double d = Haversine.distanceMeters(30.0, 120.0, 31.0, 120.0);
        printDistance("纬度差 1°", 30.0, 120.0, 31.0, 120.0, d,
                "约 111,195 米（1 纬度弧长），误差容忍 ±0.1%");
        assertEquals(111_195, d, 111_195 * 0.001);
    }

    @Test
    @DisplayName("杭州真实尺度：断桥残雪 → 平湖秋月约 1 km")
    void hangzhouRealScale() {
        // 断桥残雪 (30.2586, 120.1517) / 平湖秋月 (30.2534, 120.1432)，步行距离约 1 km
        double d = Haversine.distanceMeters(30.2586, 120.1517, 30.2534, 120.1432);
        printDistance("西湖环线两景点", 30.2586, 120.1517, 30.2534, 120.1432, d,
                "约 1 km（地图实测 800~1200 米区间）");
        assertTrue(d > 800 && d < 1200, "期望 800~1200 米，实际 " + d);
    }

    @Test
    @DisplayName("BigDecimal 入口与 double 入口结果一致（POI 库字段类型）")
    void bigDecimalEntry() {
        double viaDouble = Haversine.distanceMeters(30.2586, 120.1517, 30.2534, 120.1432);
        double viaBd = Haversine.distanceMeters(
                new BigDecimal("30.2586"), new BigDecimal("120.1517"),
                new BigDecimal("30.2534"), new BigDecimal("120.1432"));
        System.out.printf("%n  BigDecimal 入口 %.6f 米 vs double 入口 %.6f 米（应一致）%n",
                viaBd, viaDouble);
        assertEquals(viaDouble, viaBd, 1e-6);
    }
}
