package com.tripmap.tool.commute.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripmap.tool.amap.AmapClient;
import com.tripmap.tool.commute.CommuteMode;
import com.tripmap.tool.commute.config.CommuteMatrixProperties;
import com.tripmap.tool.commute.dto.CommutePairMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高德路径规划 v5 业务封装（任务 2.4.1，amap-route-api.md §2/§3）。
 *
 * <p>只承担<b>参数组装与响应解析</b>：HTTP 层的密钥注入、QPS 令牌桶、超时、
 * 指数退避重试全部复用 {@link AmapClient}（任务 1.1.2），本类一字不重写。</p>
 *
 * <p><b>参数要点</b>（amap-route-api.md 踩坑点）：</p>
 * <ul>
 *   <li>矩阵只要"多久、多远"：{@code show_fields=cost}（§2.3），不取
 *       polyline/navi/tmcs，省配额与流量；</li>
 *   <li>公交 {@code city1} 必传且<b>只认 citycode</b>（§3.1，如杭州 0571），
 *       传中文城市名直接报 20001；矩阵只要最优方案 {@code AlternativeRoute=1}；
 *       不传 date/time——通勤矩阵是出行方式基准耗时，非特定时刻规划；</li>
 *   <li>坐标 GCJ-02、经度在前、小数点后不超过 6 位（§1.3），库中 POI 坐标
 *       即高德采集原始值（GCJ-02），直接可用。</li>
 * </ul>
 *
 * <p><b>解析容错</b>：耗时取方案第一条（驾车 {@code paths[0].cost.duration}、
 * 公交 {@code transits[0].cost.duration}，秒）；{@code cost} 兼容对象（v5）与
 * 字符串（v3 票价）两种形态，v3 形态回退 {@code transits[].duration} /
 * {@code paths[].duration}（amap-route-api.md §3.4/§4.1）；方案列表为空或
 * 耗时字段缺失<b>不是 API 失败</b>——返回不可达（duration 为 null），
 * 交由矩阵层降级，绝不编造数值。</p>
 */
public class AmapRouteClient {

    private static final Logger log = LoggerFactory.getLogger(AmapRouteClient.class);

    /** 驾车路径规划 v5（amap-route-api.md §2.1） */
    static final String PATH_DRIVING = "/v5/direction/driving";
    /** 公交路径规划 v5（amap-route-api.md §3.1） */
    static final String PATH_TRANSIT = "/v5/direction/transit/integrated";
    /** 返回字段控制：只要时间与费用成本（amap-route-api.md §2.3/§3.3） */
    static final String SHOW_FIELDS_COST = "cost";

    private final AmapClient amapClient;
    private final CommuteMatrixProperties properties;

    public AmapRouteClient(AmapClient amapClient, CommuteMatrixProperties properties) {
        this.amapClient = amapClient;
        this.properties = properties;
    }

    /**
     * 查询单对 POI 通勤度量。
     *
     * @param mode      出行方式（公交需 citycode）
     * @param cityCode  起点城市 citycode（仅公交使用，如 0571；驾车可传 null）
     * @param originLng 起点经度（GCJ-02）
     * @param originLat 起点纬度（GCJ-02）
     * @param destLng   终点经度（GCJ-02）
     * @param destLat   终点纬度（GCJ-02）
     * @return 通勤度量；不可达（高德返回空方案/耗时缺失）时 duration 为 null
     * @throws com.tripmap.common.exception.ExternalApiException 高德调用失败（重试耗尽，
     *         携 retryable 标记，供矩阵层单项降级）
     */
    public CommutePairMetric query(CommuteMode mode, String cityCode,
                                   BigDecimal originLng, BigDecimal originLat,
                                   BigDecimal destLng, BigDecimal destLat) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", formatCoord(originLng, originLat));
        params.put("destination", formatCoord(destLng, destLat));
        params.put("show_fields", SHOW_FIELDS_COST);

        final String path;
        if (mode == CommuteMode.BUS) {
            path = PATH_TRANSIT;
            // 同城：city1 必传（仅 citycode），city2 省略（amap-route-api.md §3.1）
            params.put("city1", cityCode);
            params.put("strategy", String.valueOf(properties.getTransitStrategy()));
            params.put("AlternativeRoute", String.valueOf(properties.getAlternativeRoute()));
        } else {
            path = PATH_DRIVING;
            params.put("strategy", String.valueOf(properties.getDrivingStrategy()));
        }

        JsonNode root = amapClient.get(path, params);
        CommutePairMetric metric = parseFirstPlan(root, mode);
        log.debug("通勤查询: mode={}, {}→{} = {}（距离 {} 米）",
                mode, params.get("origin"), params.get("destination"),
                metric.getDurationSeconds() == null ? "不可达" : metric.getDurationSeconds() + "s",
                metric.getDistanceMeters());
        return metric;
    }

    /**
     * 解析最优方案：耗时（秒）与距离（米）。
     * 包级可见供单测直灌 JSON 验证各解析分支。
     */
    static CommutePairMetric parseFirstPlan(JsonNode root, CommuteMode mode) {
        JsonNode plans = root.path("route")
                .path(mode == CommuteMode.BUS ? "transits" : "paths");
        if (!plans.isArray() || plans.isEmpty()) {
            return new CommutePairMetric(null, null);
        }
        JsonNode first = plans.get(0);
        Long duration = extractDuration(first);
        Long distance = parseLongOrNull(first.path("distance"));
        return new CommutePairMetric(duration, distance);
    }

    /**
     * 提取方案总耗时（秒）：v5 取 cost.duration（cost 对象）；cost 为字符串
     * （v3 票价）或缺失时回退 v3 平铺字段 duration（amap-route-api.md §3.4）。
     * 耗时字段缺失/非数值返回 null（不可达，不编造）。
     */
    private static Long extractDuration(JsonNode plan) {
        JsonNode cost = plan.path("cost");
        Long duration = null;
        if (cost.isObject()) {
            duration = parseLongOrNull(cost.path("duration"));
        }
        if (duration == null) {
            duration = parseLongOrNull(plan.path("duration"));
        }
        return duration;
    }

    /** 宽容解析整数字符串：空/缺失/非数值一律 null（高德数值字段均为字符串） */
    private static Long parseLongOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 坐标格式化：{@code 经度,纬度}，经度在前，小数点后不超过 6 位
     * （amap-route-api.md §1.3）。
     */
    static String formatCoord(BigDecimal lng, BigDecimal lat) {
        return strip6(lng) + "," + strip6(lat);
    }

    private static String strip6(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("坐标不允许为 null");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
