package com.tripmap.tool.weather.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripmap.tool.weather.config.QWeatherProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 和风客户端集成测试（任务 2.3.1）：真实调用和风逐日预报 1 次。
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

    @Test
    void daily真实调用返回未来天气() {
        String host = System.getenv("QWEATHER_API_HOST");
        String key = System.getenv("QWEATHER_API_KEY");
        assumeTrue(StringUtils.hasText(host) && StringUtils.hasText(key),
                "未设置环境变量 QWEATHER_API_HOST / QWEATHER_API_KEY，跳过真实调用");

        QWeatherProperties properties = new QWeatherProperties();
        properties.setApiHost(host);
        properties.setApiKey(key);
        QWeatherClient client = new QWeatherClient(QWeatherClient.buildRestClient(properties), properties);

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
}
