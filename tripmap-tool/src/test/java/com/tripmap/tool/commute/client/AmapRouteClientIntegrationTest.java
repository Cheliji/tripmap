package com.tripmap.tool.commute.client;

import com.tripmap.tool.amap.AmapClient;
import com.tripmap.tool.amap.AmapProperties;
import com.tripmap.tool.commute.CommuteMode;
import com.tripmap.tool.commute.config.CommuteMatrixProperties;
import com.tripmap.tool.commute.dto.CommutePairMetric;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 高德路径规划客户端集成测试（任务 2.4.4）：真实调用驾车/公交路径规划。
 *
 * <p>打 {@code @Tag("integration")}，默认被 surefire 排除（父 POM {@code test.excluded.groups}），
 * 手动执行：</p>
 * <pre>mvn -pl tripmap-tool test "-Dtest=AmapRouteClientIntegrationTest" "-Dtest.excluded.groups="</pre>
 *
 * <p>前置：环境变量 {@code AMAP_API_KEY} 已注入（未注入时按假设跳过，不判失败）。
 * 配额敏感：3 对 POI × 2 种出行方式 = 6 次调用，勿追加更多。</p>
 */
@Tag("integration")
class AmapRouteClientIntegrationTest {

    /** 杭州真实 POI（GCJ-02 坐标，来自高德 POI 采集口径） */
    private static final Map<String, BigDecimal[]> HANGZHOU_POIS = new LinkedHashMap<>();
    static {
        HANGZHOU_POIS.put("西湖(断桥)", new BigDecimal[]{new BigDecimal("120.156443"), new BigDecimal("30.258903")});
        HANGZHOU_POIS.put("灵隐寺", new BigDecimal[]{new BigDecimal("120.101669"), new BigDecimal("30.242349")});
        HANGZHOU_POIS.put("河坊街", new BigDecimal[]{new BigDecimal("120.171598"), new BigDecimal("30.239152")});
    }

    @Test
    void 杭州三POI驾车与公交3x3矩阵真实调用() {
        String apiKey = System.getenv("AMAP_API_KEY");
        assumeTrue(StringUtils.hasText(apiKey), "未设置环境变量 AMAP_API_KEY，跳过真实调用");

        AmapProperties properties = new AmapProperties();
        properties.setApiKey(apiKey);
        AmapRouteClient routeClient = new AmapRouteClient(
                new AmapClient(AmapClient.buildRestClient(properties), properties),
                new CommuteMatrixProperties());

        for (CommuteMode mode : CommuteMode.values()) {
            System.out.println("\n========== 杭州 3×3 通勤矩阵（" + mode.getLabel()
                    + "，数据来源：高德路径规划 v5） ==========");
            System.out.printf("%-14s %-14s %-14s %s%n", "", "西湖(断桥)", "灵隐寺", "河坊街");
            for (Map.Entry<String, BigDecimal[]> from : HANGZHOU_POIS.entrySet()) {
                StringBuilder row = new StringBuilder(String.format("%-14s", from.getKey()));
                for (Map.Entry<String, BigDecimal[]> to : HANGZHOU_POIS.entrySet()) {
                    if (from.getKey().equals(to.getKey())) {
                        row.append(String.format("%-14s", "—"));
                        continue;
                    }
                    CommutePairMetric metric = routeClient.query(mode, "0571",
                            from.getValue()[0], from.getValue()[1],
                            to.getValue()[0], to.getValue()[1]);
                    assertNotNull(metric, "响应解析不应返回 null 对象");
                    String text = metric.getDurationSeconds() == null
                            ? "不可达"
                            : String.format("%.0f分钟/%sm", metric.getDurationSeconds() / 60.0,
                                    metric.getDistanceMeters() == null ? "?" : metric.getDistanceMeters());
                    row.append(String.format("%-14s", text));
                    assertTrue(metric.getDurationSeconds() == null || metric.getDurationSeconds() > 0,
                            "耗时必须为正数或显式不可达，不允许 0/负数/编造值");
                }
                System.out.println(row);
            }
        }
    }
}
