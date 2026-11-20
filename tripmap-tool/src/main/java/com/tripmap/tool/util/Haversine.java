package com.tripmap.tool.util;

import java.math.BigDecimal;

/**
 * Haversine 球面距离工具（任务 2.5.1，设计说明书 §6.2：K-Means 用 haversine 球面距离）。
 *
 * <p>输入坐标为高德 GCJ-02（与 POI 库口径一致）；GCJ-02 相对 WGS-84 的偏移
 * 在同一城市内近似为常量平移，对"两两相对距离"无影响，无需纠偏。</p>
 *
 * <p>纯函数工具类，无状态；供 2.5 聚类消费（2.6 时间窗/通勤以高德矩阵为准，
 * 不用直线距离估算通勤，AGENTS.md 原则 1）。</p>
 */
public final class Haversine {

    /** 地球平均半径（米，IUGG 推荐值） */
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private Haversine() {
    }

    /**
     * 两点球面距离（米）。
     *
     * @param lat1 点 1 纬度（度）
     * @param lon1 点 1 经度（度）
     * @param lat2 点 2 纬度（度）
     * @param lon2 点 2 经度（度）
     */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double dLat = radLat2 - radLat1;
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 两点球面距离（米），BigDecimal 坐标便捷入口（POI 库字段类型） */
    public static double distanceMeters(BigDecimal lat1, BigDecimal lon1,
                                        BigDecimal lat2, BigDecimal lon2) {
        return distanceMeters(lat1.doubleValue(), lon1.doubleValue(),
                lat2.doubleValue(), lon2.doubleValue());
    }
}
