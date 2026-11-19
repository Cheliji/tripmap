package com.tripmap.tool.weather.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripmap.tool.weather.config.QWeatherProperties;
import com.tripmap.tool.weather.dto.DailyWeather;
import com.tripmap.tool.weather.util.WeatherNormalizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 和风客户端集成测试（任务 2.3.1）：真实调用和风逐日预报。
 *
 * <p>打 {@code @Tag("integration")}，默认被 surefire 排除（父 POM {@code test.excluded.groups}），
 * 手动执行：</p>
 * <pre>mvn -pl tripmap-tool test "-Dtest=QWeatherClientIntegrationTest" "-Dtest.excluded.groups="</pre>
 *
 * <p>前置：环境变量 {@code QWEATHER_API_HOST} 与 {@code QWEATHER_API_KEY} 已注入
 * （未注入时按假设跳过，不判失败）。</p>
 */
@Tag("integration")
class QWeatherClientIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Test
    void daily真实调用返回未来天气() {
        String host = System.getenv("QWEATHER_API_HOST");
        String key = System.getenv("QWEATHER_API_KEY");
        assumeTrue(StringUtils.hasText(host) && StringUtils.hasText(key),
                "未设置环境变量 QWEATHER_API_HOST / QWEATHER_API_KEY，跳过真实调用");

        QWeatherClient client = newClient(host, key);

        // 真实调用 1 次：杭州城市中心坐标，未来 3 天（配额敏感，勿追加更多调用）
        JsonNode root = client.getDailyForecast(30.25, 120.15, 3);

        JsonNode days = root.path("days");
        assertTrue(days.isArray() && !days.isEmpty(), "逐日预报应返回非空 days 数组");
        JsonNode first = days.get(0);
        assertFalse(first.path("forecastStartTime").asText().isBlank(), "应携带 forecastStartTime");
        assertFalse(first.path("daytime").path("condition").path("text").asText().isBlank(),
                "应携带白天天气现象");
        assertFalse(first.path("temperatureMax").path("value").asText().isBlank(), "应携带最高温");
    }

    /**
     * 查询杭州未来十天天气并打印天气表（人读演示，AGENTS.md 测试定规）。
     *
     * <p>走完整数据链路：客户端真实拉取 → {@link WeatherNormalizer} 归一化 →
     * 控制台打印逐日表（温度/天气/降水），console 可直接当演示看。</p>
     */
    @Test
    void daily杭州未来十天并打印天气表() {
        String host = System.getenv("QWEATHER_API_HOST");
        String key = System.getenv("QWEATHER_API_KEY");
        assumeTrue(StringUtils.hasText(host) && StringUtils.hasText(key),
                "未设置环境变量 QWEATHER_API_HOST / QWEATHER_API_KEY，跳过真实调用");

        QWeatherClient client = newClient(host, key);

        // 真实调用 1 次：杭州城市中心（GCJ-02），未来 10 天（v1 daily 上限）
        JsonNode root = client.getDailyForecast(30.25, 120.15, 10);
        List<DailyWeather> daily = WeatherNormalizer.normalizeDaily(root);

        System.out.println("\n========== 杭州未来十天天气（数据来源：和风天气 QWeather） ==========");
        System.out.printf("%-12s %-8s %-8s %-8s %-8s %-10s %-8s %-6s%n",
                "日期", "白天", "夜间", "最高°C", "最低°C", "降水概率%", "降水mm", "UV");
        for (DailyWeather d : daily) {
            System.out.printf("%-12s %-8s %-8s %-8s %-8s %-10s %-8s %-6s%n",
                    d.getDate(), nvl(d.getDayCondition()), nvl(d.getNightCondition()),
                    nvl(d.getTempMaxC()), nvl(d.getTempMinC()),
                    nvl(d.getPrecipProbabilityPct()), nvl(d.getPrecipMm()), nvl(d.getUvIndexMax()));
        }

        assertEquals(10, daily.size(), "请求 days=10 应返回 10 天逐日预报");
        assertEquals(LocalDate.now().format(DATE_FMT), daily.get(0).getDate(),
                "days[0] 应为今天（localTime=true）");
        assertFalse(daily.get(0).getDayCondition() == null, "应返回白天天气现象");
        assertFalse(daily.get(0).getTempMaxC() == null, "应返回当日最高温");
    }

    private QWeatherClient newClient(String host, String key) {
        QWeatherProperties properties = new QWeatherProperties();
        properties.setApiHost(host);
        properties.setApiKey(key);
        return new QWeatherClient(QWeatherClient.buildRestClient(properties), properties);
    }

    private static String nvl(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }
}
